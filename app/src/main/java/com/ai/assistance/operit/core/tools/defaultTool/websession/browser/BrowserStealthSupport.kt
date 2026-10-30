package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ai.assistance.operit.util.AppLogger
import java.util.Collections

/**
 * 内嵌 WebView 指纹加固。
 *
 * 走 WebViewCompat.addDocumentStartJavaScript（DOCUMENT_START_SCRIPT）注入，
 * 注入时机早于页面自身的 inline script 与全部外部脚本，因此页面检测代码
 * 看到的 navigator / WebGL / Canvas 全部是补丁后的值。
 * 不依赖任何外部进程或第二个浏览器。
 */
internal object BrowserStealthSupport {
    private const val TAG = "BrowserStealth"
    private val attached: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun attach(webView: WebView, sessionId: String) {
        if (!isSupported()) {
            AppLogger.w(TAG, "DOCUMENT_START_SCRIPT 不支持，跳过指纹加固")
            return
        }
        val key = sessionId.ifBlank { System.identityHashCode(webView).toString() }
        if (!attached.add(key)) return
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, STEALTH_SCRIPT, setOf("*"))
            AppLogger.i(TAG, "指纹脚本已挂载: " + key)
        }.onFailure {
            attached.remove(key)
            AppLogger.e(TAG, "指纹脚本挂载失败", it)
        }
    }

    fun detach(sessionId: String) {
        attached.remove(sessionId)
    }

    private val STEALTH_SCRIPT: String = """
(function () {
  if (window.__operitStealth) return;
  try { Object.defineProperty(window, '__operitStealth', { value: true, enumerable: false }); } catch (e) {}

  var def = function (obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, { get: function () { return value; }, configurable: true, enumerable: true });
    } catch (e) {}
  };

  var nav = window.navigator;
  try { delete Object.getPrototypeOf(nav).webdriver; } catch (e) {}
  def(nav, 'webdriver', false);
  def(nav, 'hardwareConcurrency', 8);
  def(nav, 'deviceMemory', 8);
  def(nav, 'platform', 'Linux armv8l');
  def(nav, 'vendor', 'Google Inc.');
  def(nav, 'maxTouchPoints', 5);
  def(nav, 'languages', ['zh-CN', 'zh', 'en-US', 'en']);
  def(nav, 'language', 'zh-CN');
  def(nav, 'doNotTrack', null);

  try {
    var fakePlugins = [
      { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' }
    ];
    var arr = Object.create(PluginArray.prototype);
    fakePlugins.forEach(function (p, i) {
      var plugin = Object.create(Plugin.prototype);
      def(plugin, 'name', p.name);
      def(plugin, 'filename', p.filename);
      def(plugin, 'description', p.description);
      def(plugin, 'length', 1);
      arr[i] = plugin;
    });
    def(arr, 'length', fakePlugins.length);
    def(nav, 'plugins', arr);
  } catch (e) {}

  var origGetParameter = WebGLRenderingContext.prototype.getParameter;
  WebGLRenderingContext.prototype.getParameter = function (p) {
    if (p === 37445) return 'Google Inc. (Qualcomm)';
    if (p === 37446) return 'ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)';
    if (p === 7936) return 'WebKit';
    if (p === 7937) return 'WebKit WebGL';
    return origGetParameter.apply(this, arguments);
  };
  if (window.WebGL2RenderingContext) {
    var orig2 = WebGL2RenderingContext.prototype.getParameter;
    WebGL2RenderingContext.prototype.getParameter = function (p) {
      if (p === 37445) return 'Google Inc. (Qualcomm)';
      if (p === 37446) return 'ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)';
      return orig2.apply(this, arguments);
    };
  }

  var seed = 0;
  var noise = function () {
    seed = (seed * 9301 + 49297) % 233280;
    return seed / 233280;
  };
  var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
  HTMLCanvasElement.prototype.toDataURL = function () {
    try {
      var ctx = this.getContext('2d');
      if (ctx && this.width > 0 && this.height > 0) {
        var img = ctx.getImageData(0, 0, 1, 1);
        img.data[0] = (img.data[0] + Math.floor(noise() * 3)) % 256;
        ctx.putImageData(img, 0, 0);
      }
    } catch (e) {}
    return origToDataURL.apply(this, arguments);
  };

  try {
    var origQuery = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = function (params) {
      if (params && params.name === 'notifications') {
        return Promise.resolve({ state: Notification.permission, onchange: null });
      }
      return origQuery(params);
    };
  } catch (e) {}

  def(nav, 'connection', {
    effectiveType: '4g', rtt: 50, downlink: 10, saveData: false,
    onchange: null, addEventListener: function () {}, removeEventListener: function () {}
  });

  if (!window.chrome) {
    try {
      Object.defineProperty(window, 'chrome', {
        value: { runtime: {}, app: { isInstalled: false }, csi: function () {}, loadTimes: function () {} },
        configurable: true, enumerable: true
      });
    } catch (e) {}
  }

  try {
    var origToString = Function.prototype.toString;
    Function.prototype.toString = function () {
      if (this === WebGLRenderingContext.prototype.getParameter ||
          this === HTMLCanvasElement.prototype.toDataURL ||
          this === navigator.permissions.query) {
        return 'function () { [native code] }';
      }
      return origToString.apply(this, arguments);
    };
  } catch (e) {}

  try {
    var origOuterW = window.outerWidth, origOuterH = window.outerHeight;
    def(window, 'outerWidth', window.innerWidth);
    def(window, 'outerHeight', window.innerHeight + 74);
    def(window, 'screenX', 0);
    def(window, 'screenY', 0);
  } catch (e) {}
})();
"""
}
// ci-touch: trigger cloud build via push (Actions dispatch disabled on fork)
