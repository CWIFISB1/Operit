#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Operit 隧道 —— 把订阅节点转成本地 SOCKS5 端口。

设计目标：
  1. 零外部二进制依赖：只用 python3 标准库 + cryptography（Operit 自带）
  2. 静默降级：不打印能力矩阵，能用就用，不能用就退
  3. 内存恒定：逐块转发，不缓存整条流

用法：
  python3 operit-tunnel.py --config /path/node.json --port 17001

支持：http(CONNECT) / socks5 / ss(aead) / trojan / vless(tcp+tls)
不支持：hysteria2 / tuic（需 QUIC）
"""

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import threading
import time

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM, ChaCha20Poly1305
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives import hashes
    HAVE_CRYPTO = True
except Exception:
    HAVE_CRYPTO = False

BUFSIZE = 32768
DEBUG = bool(os.environ.get("OPERIT_TUNNEL_DEBUG"))


def log(msg):
    if DEBUG:
        sys.stderr.write("[tunnel] %s\n" % msg)
        sys.stderr.flush()


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def evp_bytes_to_key(password, key_len):
    m = b""
    prev = b""
    while len(m) < key_len:
        prev = hashlib.md5(prev + password).digest()
        m += prev
    return m[:key_len]


def hkdf_sha1(key, salt, info, length):
    return HKDF(algorithm=hashes.SHA1(), length=length, salt=salt, info=info).derive(key)


def resolve_addr(host, port):
    try:
        infos = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM)
        if infos:
            return infos[0][4]
    except Exception:
        pass
    infos = socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM)
    return infos[0][4]


def encode_socks_addr(host, port):
    try:
        return b"\x01" + socket.inet_aton(host) + struct.pack(">H", port)
    except OSError:
        pass
    try:
        return b"\x04" + socket.inet_pton(socket.AF_INET6, host) + struct.pack(">H", port)
    except OSError:
        pass
    hb = host.encode("idna") if any(ord(c) > 127 for c in host) else host.encode()
    return b"\x03" + bytes([len(hb)]) + hb + struct.pack(">H", port)


def tls_wrap(sock, sni):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        ctx.set_alpn_protocols(["http/1.1"])
    except Exception:
        pass
    return ctx.wrap_socket(sock, server_hostname=sni or None)


SS_METHODS = {
    "aes-128-gcm": (16, AESGCM),
    "aes-256-gcm": (32, AESGCM),
    "chacha20-ietf-poly1305": (32, ChaCha20Poly1305),
    "chacha20-poly1305": (32, ChaCha20Poly1305),
}


class SsConn:
    def __init__(self, sock, password, method):
        key_len, aead_cls = SS_METHODS[method]
        master = evp_bytes_to_key(password.encode(), key_len)
        self.sock = sock

        w_salt = os.urandom(key_len)
        w_key = hkdf_sha1(master, w_salt, b"ss-subkey", key_len)
        self.w_aead = aead_cls(w_key)
        self.w_ctr = 0
        self.sock.sendall(w_salt)

        r_salt = recv_exact(sock, key_len)
        if r_salt is None:
            raise IOError("no ss salt")
        r_key = hkdf_sha1(master, r_salt, b"ss-subkey", key_len)
        self.r_aead = aead_cls(r_key)
        self.r_ctr = 0
        self.rbuf = b""

    def _wn(self):
        n = self.w_ctr.to_bytes(12, "little")
        self.w_ctr += 1
        return n

    def _rn(self):
        n = self.r_ctr.to_bytes(12, "little")
        self.r_ctr += 1
        return n

    def send(self, data):
        if not data:
            return
        out = b""
        for i in range(0, len(data), 0x3FFF):
            chunk = data[i:i + 0x3FFF]
            out += self.w_aead.encrypt(self._wn(), struct.pack(">H", len(chunk)), None)
            out += self.w_aead.encrypt(self._wn(), chunk, None)
        self.sock.sendall(out)

    def recv(self, n):
        while len(self.rbuf) < n:
            head = recv_exact(self.sock, 2 + 16)
            if head is None:
                break
            plen = struct.unpack(">H", self.r_aead.decrypt(self._rn(), head, None))[0]
            body = recv_exact(self.sock, plen + 16)
            if body is None:
                break
            self.rbuf += self.r_aead.decrypt(self._rn(), body, None)
        out, self.rbuf = self.rbuf[:n], self.rbuf[n:]
        return out


def uuid_to_bytes(u):
    h = u.replace("-", "")
    if len(h) != 32:
        raise ValueError("bad uuid")
    return bytes.fromhex(h)


class VlessConn:
    def __init__(self, sock, uuid, host, port, use_tls=False, sni=None):
        self.sock = tls_wrap(sock, sni or host) if use_tls else sock
        self.uuid = uuid_to_bytes(uuid)
        self.host = host
        self.port = port
        self.rbuf = b""

    def handshake(self):
        addr = encode_socks_addr(self.host, self.port)
        head = b"\x00" + self.uuid + struct.pack(">H", len(addr) + 1) + b"\x01" + addr
        self.sock.sendall(head)
        v = recv_exact(self.sock, 2)
        if v is None:
            raise IOError("vless no response")
        if v[1]:
            recv_exact(self.sock, v[1])

    def send(self, data):
        if data:
            self.sock.sendall(data)

    def recv(self, n):
        if len(self.rbuf) >= n:
            out, self.rbuf = self.rbuf[:n], self.rbuf[n:]
            return out
        chunk = self.sock.recv(BUFSIZE)
        if not chunk:
            return self.rbuf[:n] if self.rbuf else b""
        self.rbuf += chunk
        out, self.rbuf = self.rbuf[:n], self.rbuf[n:]
        return out


class TrojanConn:
    def __init__(self, sock, password, host, port, sni=None):
        self.sock = tls_wrap(sock, sni or host)
        self.password = password
        self.host = host
        self.port = port

    def handshake(self):
        pwd = hashlib.sha224(self.password.encode()).hexdigest().encode()
        head = pwd + b"\r\n" + b"\x01" + encode_socks_addr(self.host, self.port) + b"\r\n"
        self.sock.sendall(head)

    def send(self, data):
        if data:
            self.sock.sendall(data)

    def recv(self, n):
        return self.sock.recv(n)


class HttpConn:
    def __init__(self, sock, host, port, user_info=None):
        self.sock = sock
        self.host = host
        self.port = port
        self.user_info = user_info

    def handshake(self):
        req = "CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n" % (self.host, self.port, self.host, self.port)
        if self.user_info:
            req += "Proxy-Authorization: Basic %s\r\n" % base64.b64encode(
                self.user_info.encode()).decode()
        req += "\r\n"
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            c = self.sock.recv(1)
            if not c:
                break
            buf += c
        first = buf.split(b"\r\n")[0]
        if b" 200" not in first:
            raise IOError("http connect failed: %s" % first[:80])

    def send(self, data):
        if data:
            self.sock.sendall(data)

    def recv(self, n):
        return self.sock.recv(n)


class DirectConn:
    """不经代理，直接连目标。用于节点本身就是本地 socks5/http 时的兜底。"""

    def __init__(self, sock):
        self.sock = sock

    def handshake(self):
        pass

    def send(self, data):
        if data:
            self.sock.sendall(data)

    def recv(self, n):
        return self.sock.recv(n)


def build_outbound(node, target_host, target_port):
    t = (node.get("type") or "").lower()
    host = node["host"]
    port = int(node["port"])
    extra = node.get("extra") or {}

    if t == "direct":
        raw = socket.create_connection(resolve_addr(target_host, target_port), timeout=20)
        return DirectConn(raw)

    raw = socket.create_connection(resolve_addr(host, port), timeout=20)

    if t in ("socks5", "socks", "http"):
        c = HttpConn(raw, target_host, target_port, extra.get("userInfo"))
        c.handshake()
        return c
    if t == "ss":
        c = SsConn(raw, extra.get("password") or extra.get("userInfo") or "",
                   extra.get("method", "aes-256-gcm"))
        c.send(encode_socks_addr(target_host, target_port))
        return c
    if t == "trojan":
        # trojan://<password>@host:port 的密码同样落在 userInfo
        pwd = extra.get("password") or extra.get("userInfo") or ""
        c = TrojanConn(raw, pwd, target_host, target_port,
                       extra.get("sni") or extra.get("peer") or extra.get("host"))
        c.handshake()
        return c
    if t == "vless":
        # 订阅里 vless://<uuid>@host:port 的 uuid 落在 userInfo，不是 uuid 键
        uid = extra.get("uuid") or extra.get("userInfo") or ""
        c = VlessConn(raw, uid, target_host, target_port,
                      extra.get("tls") == "1", extra.get("sni"))
        c.handshake()
        return c

    raw.close()
    raise ValueError("unsupported: %s" % t)


def pump_up(client, outbound):
    try:
        while True:
            data = client.recv(BUFSIZE)
            if not data:
                break
            outbound.send(data)
    except Exception:
        pass
    try:
        outbound.sock.shutdown(socket.SHUT_WR)
    except Exception:
        pass


def pump_down(outbound, client):
    try:
        while True:
            data = outbound.recv(BUFSIZE)
            if not data:
                break
            client.sendall(data)
    except Exception:
        pass
    try:
        client.shutdown(socket.SHUT_WR)
    except Exception:
        pass


def handle_client(client, node):
    try:
        client.settimeout(60)
        head = recv_exact(client, 2)
        if not head or head[0] != 0x05:
            return
        if head[1]:
            recv_exact(client, head[1])
        client.sendall(b"\x05\x00")

        req = recv_exact(client, 4)
        if not req or req[1] != 0x01:
            client.sendall(b"\x05\x07\x00\x01" + b"\x00" * 6)
            return

        atyp = req[3]
        if atyp == 0x01:
            host = socket.inet_ntoa(recv_exact(client, 4))
        elif atyp == 0x03:
            ln = recv_exact(client, 1)[0]
            host = recv_exact(client, ln).decode("utf-8", "ignore")
        elif atyp == 0x04:
            host = socket.inet_ntop(socket.AF_INET6, recv_exact(client, 16))
        else:
            return
        port = struct.unpack(">H", recv_exact(client, 2))[0]

        try:
            outbound = build_outbound(node, host, port)
        except Exception as e:
            log("outbound fail %s:%s %s" % (host, port, e))
            client.sendall(b"\x05\x05\x00\x01" + b"\x00" * 6)
            return

        client.sendall(b"\x05\x00\x00\x01" + b"\x00" * 6)
        client.settimeout(None)

        t = threading.Thread(target=pump_down, args=(outbound, client), daemon=True)
        t.start()
        pump_up(client, outbound)
        t.join(timeout=5)
    except Exception as e:
        log("client error %s" % e)
    finally:
        try:
            client.close()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--bind", default="127.0.0.1")
    args = ap.parse_args()

    with open(args.config, "r", encoding="utf-8") as f:
        node = json.load(f)

    t = (node.get("type") or "").lower()
    if t == "ss" and not HAVE_CRYPTO:
        sys.stderr.write("cryptography missing\n")
        sys.exit(2)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.bind, args.port))
    srv.listen(64)
    sys.stderr.write("listening %s:%d type=%s\n" % (args.bind, args.port, t))
    sys.stderr.flush()

    while True:
        try:
            client, _ = srv.accept()
            threading.Thread(target=handle_client, args=(client, node), daemon=True).start()
        except Exception as e:
            log("accept error %s" % e)
            time.sleep(0.2)


if __name__ == "__main__":
    main()