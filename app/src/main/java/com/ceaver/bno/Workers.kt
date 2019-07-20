package com.ceaver.bno

import android.content.Context
import androidx.work.*
import com.ceaver.bno.bitnodes.BitnodesRepository
import com.ceaver.bno.nodes.NodeRepository
import com.ceaver.bno.snapshots.Snapshot
import com.ceaver.bno.snapshots.SnapshotRepository
import com.ceaver.bno.threading.BackgroundThreadExecutor
import org.greenrobot.eventbus.EventBus

private const val NODE_WORKER_NODE_ID = "com.ceaver.bno.Workers.nodeId"

object Workers {

    fun run() {
        run(null)
    }

    fun run(nodeId: Long?) {
        BackgroundThreadExecutor.execute {
            WorkManager.getInstance()
                .beginWith(notifyStart())
                .then(prepareNodes(nodeId))
                .then(updateNodes(nodeId) + updateSnapshot())
                .then(notifyEnd())
                .enqueue()
        }
    }

    private fun notifyStart(): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<StartWorker>().build()
    }

    class StartWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
        override fun doWork(): Result {
            EventBus.getDefault().post(WorkerEvents.Start())
            return Result.success()
        }
    }

    private fun updateSnapshot(): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<SnapshotWorker>().build()
    }

    class SnapshotWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
        override fun doWork(): Result {
            EventBus.getDefault().post(WorkerEvents.SnapshotWorkerStart())
            val response = BitnodesRepository.lookupLatestSnapshot()
            if (response.isSuccessful()) {
                val bitnodesSnapshot = response.result!!
                SnapshotRepository.update(Snapshot(bitnodesSnapshot))
            }
            EventBus.getDefault().post(WorkerEvents.SnapshotWorkerEnd(response.error))
            return Result.success()
        }
    }

    private fun prepareNodes(nodeId: Long?): OneTimeWorkRequest {
        return if (nodeId == null) {
            OneTimeWorkRequestBuilder<PrepareNodesWorker>().build()
        } else {
            val data = Data.Builder().putLong(NODE_WORKER_NODE_ID, nodeId).build()
            OneTimeWorkRequestBuilder<PrepareNodesWorker>().setInputData(data).build()
        }
    }

    class PrepareNodesWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
        override fun doWork(): Result {
            val nodeId = inputData.getLong(NODE_WORKER_NODE_ID, -1)
            if (nodeId == -1L) {
                NodeRepository.updateNodes(NodeRepository.loadNodes().map { it.copyForReload() }, true)
            } else {
                NodeRepository.updateNode(NodeRepository.loadNode(nodeId).copyForReload(), true)
            }
            return Result.success()
        }
    }

    private fun updateNodes(nodeId: Long?): List<OneTimeWorkRequest> {
        val nodes = if (nodeId == null) NodeRepository.loadNodes() else listOf(NodeRepository.loadNode(nodeId))
        return nodes.map {
            val data = Data.Builder().putLong(NODE_WORKER_NODE_ID, it.id).build()
            OneTimeWorkRequestBuilder<UpdateNodeWorker>().setInputData(data).build()
        }
    }

    class UpdateNodeWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
        override fun doWork(): Result {
            val node = NodeRepository.loadNode(inputData.getLong(NODE_WORKER_NODE_ID, -1))
            val response = BitnodesRepository.lookupNode(node.ip, node.port)
            NodeRepository.updateNode(node.copyFromBitnodesResponse(response), true)
            return Result.success()
        }
    }

    private fun notifyEnd(): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<EndWorker>().build()
    }

    class EndWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
        override fun doWork(): Result {
            EventBus.getDefault().post(WorkerEvents.End())
            return Result.success()
        }
    }
}