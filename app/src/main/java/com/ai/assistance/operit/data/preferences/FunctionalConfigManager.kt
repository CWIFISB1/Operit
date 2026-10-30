package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.ai.assistance.operit.data.model.FunctionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 为功能配置创建专用的DataStore
private val Context.functionalConfigDataStore: DataStore<Preferences> by
        versionedPreferencesDataStore(
                name = "functional_configs",
                currentVersion = 1,
        ) {
            preferenceSchemaMigration { version, preferences ->
                when (version) {
                    0 -> FunctionalConfigManager.migratePreferencesFromVersionZero(preferences)
                    else -> missingPreferencesSchemaMigration(version)
                }
            }
        }

/** 功能配置映射数据，包含配置ID和模型索引 */
@Serializable
data class FunctionConfigMapping(
    val configId: String = FunctionalConfigManager.DEFAULT_CONFIG_ID,
    val modelIndex: Int = 0,
    /**
     * 动态跟随对话模型（CHAT）的绑定。
     *
     * 语义对齐 Operit2 的 FunctionalConfigManager.followsChat：
     * - 仅非 CHAT 功能可为 true，CHAT 自身恒为 false
     * - 为 true 时实际生效的 configId/modelIndex 始终取 CHAT 的当前绑定
     * - 保存时先把 followsChat 项同步成 CHAT 的快照，保证存储自洽
     */
    val followsChat: Boolean = false
)

internal data class FunctionConfigMappingRepair(
    val mapping: Map<FunctionType, FunctionConfigMapping>,
    val affectedFunctions: List<FunctionType>,
)

internal fun remapDeletedConfigReferences(
    mapping: Map<FunctionType, FunctionConfigMapping>,
    deletedConfigId: String,
): FunctionConfigMappingRepair {
    val updatedMapping = mapping.toMutableMap()
    val affectedFunctions = mapping
        .filterValues { it.configId == deletedConfigId }
        .keys
        .sortedBy { it.name }

    affectedFunctions.forEach { functionType ->
        // 跟随对话模型的项不指向被删配置，保持原状
        if (updatedMapping[functionType]?.followsChat == true) {
            return@forEach
        }
        updatedMapping[functionType] = FunctionConfigMapping(
            configId = FunctionalConfigManager.DEFAULT_CONFIG_ID,
            modelIndex = 0,
        )
    }

    return FunctionConfigMappingRepair(
        mapping = updatedMapping,
        affectedFunctions = affectedFunctions,
    )
}

/** 管理不同功能使用的模型配置 这个类用于将FunctionType映射到对应的ModelConfigID */
class FunctionalConfigManager(private val context: Context) {

    // 定义key
    companion object {
        // 功能配置映射key
        val FUNCTION_CONFIG_MAPPING = stringPreferencesKey("function_config_mapping")

        // 默认映射值
        const val DEFAULT_CONFIG_ID = "default"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun migratePreferencesFromVersionZero(preferences: MutablePreferences) {
            val mapping = readSchemaZeroMapping(preferences[FUNCTION_CONFIG_MAPPING])
            writeFunctionConfigMapping(preferences, mapping)
        }

        private fun readSchemaZeroMapping(raw: String?): Map<FunctionType, FunctionConfigMapping> {
            if (raw.isNullOrBlank() || raw == "{}") {
                return defaultMapping()
            }

            val decoded =
                    try {
                        json.decodeFromString<Map<String, FunctionConfigMapping>>(raw)
                    } catch (error: SerializationException) {
                        json.decodeFromString<Map<String, String>>(raw)
                                .mapValues { (_, configId) -> FunctionConfigMapping(configId, 0) }
                    }

            return normalizeMapping(decoded)
        }

        private fun readFunctionConfigMapping(
                preferences: Preferences
        ): Map<FunctionType, FunctionConfigMapping> {
            val raw = preferences[FUNCTION_CONFIG_MAPPING]
            if (raw.isNullOrBlank() || raw == "{}") {
                return defaultMapping()
            }
            return normalizeMapping(json.decodeFromString<Map<String, FunctionConfigMapping>>(raw))
        }

        private fun writeFunctionConfigMapping(
                preferences: MutablePreferences,
                mapping: Map<FunctionType, FunctionConfigMapping>
        ) {
            val stringMapping = mapping.entries.associate { it.key.name to it.value }
            preferences[FUNCTION_CONFIG_MAPPING] = json.encodeToString(stringMapping)
        }

        private fun normalizeMapping(
                decoded: Map<String, FunctionConfigMapping>
        ): Map<FunctionType, FunctionConfigMapping> {
            val normalized = defaultMapping().toMutableMap()
            val functionTypesByName = FunctionType.values().associateBy { it.name }
            decoded.forEach { (key, value) ->
                val functionType = functionTypesByName[key] ?: return@forEach
                normalized[functionType] = value
            }
            return normalizeFollowChat(normalized)
        }

        /**
         * 对齐 O2 的 follow-chat 归一化：
         * - CHAT 自身永不为 followsChat
         * - 所有 followsChat 功能，把 configId/modelIndex 快照成 CHAT 当前值
         *
         * 注意：入参是不可变 Map，这里必须构造新 Map 返回，不能原地赋值。
         */
        private fun normalizeFollowChat(
                mapping: Map<FunctionType, FunctionConfigMapping>
        ): Map<FunctionType, FunctionConfigMapping> {
            val rawChat = mapping[FunctionType.CHAT]
            val chat = (rawChat ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0, false))
                    .copy(followsChat = false)
            val result = mutableMapOf<FunctionType, FunctionConfigMapping>()
            mapping.forEach { (functionType, value) ->
                result[functionType] =
                        if (functionType == FunctionType.CHAT) {
                            chat
                        } else if (value.followsChat) {
                            FunctionConfigMapping(
                                    configId = chat.configId,
                                    modelIndex = chat.modelIndex,
                                    followsChat = true
                            )
                        } else {
                            value
                        }
            }
            if (!result.containsKey(FunctionType.CHAT)) {
                result[FunctionType.CHAT] = chat
            }
            return result
        }

        private fun defaultMapping(): Map<FunctionType, FunctionConfigMapping> {
            return FunctionType.values().associateWith { FunctionConfigMapping(DEFAULT_CONFIG_ID, 0) }
        }
    }

    // 获取完整的功能配置映射（包含modelIndex）
    val functionConfigMappingWithIndexFlow: Flow<Map<FunctionType, FunctionConfigMapping>> =
            context.functionalConfigDataStore.data.map { preferences ->
                readFunctionConfigMapping(preferences)
            }

    // 获取功能配置映射（保持向后兼容）
    val functionConfigMappingFlow: Flow<Map<FunctionType, String>> =
            functionConfigMappingWithIndexFlow.map { mapping ->
                mapping.entries.associate { it.key to it.value.configId }
            }

    // 保存功能配置映射（保持向后兼容）
    suspend fun saveFunctionConfigMapping(mapping: Map<FunctionType, String>) {
        val mappingWithIndex = mapping.entries.associate { 
            it.key to FunctionConfigMapping(it.value, 0) 
        }
        saveFunctionConfigMappingWithIndex(mappingWithIndex)
    }

    // 保存功能配置映射（包含modelIndex）
    suspend fun saveFunctionConfigMappingWithIndex(mapping: Map<FunctionType, FunctionConfigMapping>) {
        context.functionalConfigDataStore.edit { preferences ->
            writeFunctionConfigMapping(preferences, mapping)
        }
    }

    // 获取指定功能的配置ID
    suspend fun getConfigIdForFunction(functionType: FunctionType): String {
        val mapping = functionConfigMappingFlow.first()
        return mapping[functionType] ?: DEFAULT_CONFIG_ID
    }

    // 获取指定功能的完整配置（包含modelIndex）
    suspend fun getConfigMappingForFunction(functionType: FunctionType): FunctionConfigMapping {
        val mapping = functionConfigMappingWithIndexFlow.first()
        return mapping[functionType] ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
    }

    /**
     * 返回实际生效的绑定：若该功能 followsChat，则直接取对话模型（CHAT）的当前绑定。
     * 对齐 O2 的 resolveEffectiveBinding。
     */
    suspend fun getEffectiveConfigMappingForFunction(
            functionType: FunctionType
    ): FunctionConfigMapping {
        val mapping = functionConfigMappingWithIndexFlow.first()
        val self = mapping[functionType] ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
        if (functionType != FunctionType.CHAT && self.followsChat) {
            val chat = mapping[FunctionType.CHAT]
            if (chat != null) {
                return chat.copy(followsChat = false)
            }
        }
        return self.copy(followsChat = false)
    }

    /**
     * 判断某功能当前是否跟随对话模型。
     */
    suspend fun isFunctionFollowingChat(functionType: FunctionType): Boolean {
        if (functionType == FunctionType.CHAT) return false
        val mapping = functionConfigMappingWithIndexFlow.first()
        return mapping[functionType]?.followsChat == true
    }

    // 设置指定功能的配置ID
    suspend fun setConfigForFunction(functionType: FunctionType, configId: String) {
        setConfigForFunction(functionType, configId, 0)
    }

    // 设置指定功能的配置ID和模型索引
    suspend fun setConfigForFunction(functionType: FunctionType, configId: String, modelIndex: Int) {
        val mapping = functionConfigMappingWithIndexFlow.first().toMutableMap()
        mapping[functionType] = FunctionConfigMapping(configId, modelIndex)
        saveFunctionConfigMappingWithIndex(mapping)
    }

        /**
     * 让指定功能动态跟随对话模型（CHAT）。
     * CHAT 自身不允许跟随自己。
     */
    suspend fun setFunctionFollowChat(functionType: FunctionType) {
        require(functionType != FunctionType.CHAT) { "chat binding cannot follow itself" }
        val current = functionConfigMappingWithIndexFlow.first().toMutableMap()
        val chat = current[FunctionType.CHAT] ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
        current[functionType] = FunctionConfigMapping(
                configId = chat.configId,
                modelIndex = chat.modelIndex,
                followsChat = true
        )
        saveFunctionConfigMappingWithIndex(normalizeFollowChat(current))
    }

    /**
     * 让所有非 CHAT 功能统一跟随对话模型（CHAT）。
     * 这是「全部跟随」的正确语义，对齐 O2 的 setAllFunctionsFollowChat。
     */
    suspend fun setAllFunctionsFollowChat() {
        val current = functionConfigMappingWithIndexFlow.first().toMutableMap()
        val chat = current[FunctionType.CHAT] ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
        FunctionType.values()
                .filter { it != FunctionType.CHAT }
                .forEach { functionType ->
                    current[functionType] = FunctionConfigMapping(
                            configId = chat.configId,
                            modelIndex = chat.modelIndex,
                            followsChat = true
                    )
                }
        current[FunctionType.CHAT] = chat.copy(followsChat = false)
        saveFunctionConfigMappingWithIndex(normalizeFollowChat(current))
    }

// 重置指定功能的配置为默认
    suspend fun resetFunctionConfig(functionType: FunctionType) {
        setConfigForFunction(functionType, DEFAULT_CONFIG_ID)
    }

    /**
     * 「全部跟随」：所有非 CHAT 功能跟随对话模型（CHAT）。
     *
     * 注意：这与「全部重置为默认配置」不是一回事。
     * 对齐 Operit2 的 setAllFunctionsFollowChat 语义。
     */
    suspend fun resetAllFunctionConfigs() {
        setAllFunctionsFollowChat()
    }

    /**
     * 真正把所有功能重置回默认配置（含 CHAT）。
     * 保留给需要硬重置的调用方。
     */
    suspend fun resetAllFunctionConfigsToDefault() {
        val defaultMapping =
                FunctionType.values().associateWith {
                    FunctionConfigMapping(DEFAULT_CONFIG_ID, 0, false)
                }
        saveFunctionConfigMappingWithIndex(defaultMapping)
    }
}
