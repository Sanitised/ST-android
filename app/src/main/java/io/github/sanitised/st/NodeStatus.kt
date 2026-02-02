package io.github.sanitised.st

enum class NodeState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

const val DEFAULT_PORT = 8000

data class NodeStatus(
    val state: NodeState,
    val message: String = "",
    val port: Int = DEFAULT_PORT,
    val pid: Long? = null
)

interface NodeStatusListener {
    fun onStatus(status: NodeStatus)
}
