package io.graspfolio.app

/** Check before writing: neither unknown external edits nor a deleted sidecar are disposable. */
internal fun shouldLoadRemote(baseHash: String?, remoteHash: String?, dirty: Boolean): Boolean {
    if (remoteHash == null) {
        require(baseHash == null) { "原批注旁文件不见了，已保留本地副本，未重建覆盖" }
        return false
    }
    if (baseHash == remoteHash) return false
    require(!dirty) { "旁文件已变化或存在批注冲突，已保留两份数据，未覆盖" }
    return true
}
