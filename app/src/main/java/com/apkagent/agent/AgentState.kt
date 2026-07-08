package com.apkagent.agent

/**
 * Agent 运行状态
 */
data class AgentState(
    val phase: ReversePhase = ReversePhase.PREPARE,
    val isRunning: Boolean = false,
    val currentTool: String? = null,
    val toolsExecuted: Int = 0,
    val findings: List<String> = emptyList(),
    val patches: List<String> = emptyList(),
    val error: String? = null
) {
    val progress: Float
        get() = when (phase) {
            ReversePhase.PREPARE -> 0.1f
            ReversePhase.STATIC_ANALYSIS -> 0.3f
            ReversePhase.DYNAMIC -> 0.5f
            ReversePhase.MODIFICATION -> 0.7f
            ReversePhase.REPACK -> 0.9f
            ReversePhase.DONE -> 1.0f
        }

    fun addFinding(finding: String): AgentState =
        copy(findings = findings + finding)

    fun addPatch(patch: String): AgentState =
        copy(patches = patches + patch)

    fun advance(): AgentState =
        copy(phase = phase.next())
}
