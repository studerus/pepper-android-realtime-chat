package io.github.anonymous.pepper_realtime.network

import android.util.Log
import io.github.anonymous.pepper_realtime.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.util.concurrent.TimeUnit

object SshConnectionHelper {
    private const val TAG = "SshConnectionHelper"

    /**
     * Test SSH connection to Pepper robot head.
     * 
     * @param ioDispatcher Dispatcher for IO operations
     * @param scope CoroutineScope to launch the test in
     */
    fun testFixedIpSshConnect(ioDispatcher: CoroutineDispatcher, scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            val host = "198.18.0.1" // Known working IP

            try {
                SSHClient(createConfig()).use { ssh ->
                    Log.i(TAG, "[SSH-TEST] Connecting to $host")

                    ssh.connectTimeout = 3000
                    ssh.timeout = 5000
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connect(host, 22)

                    val password = BuildConfig.PEPPER_SSH_PASSWORD
                    if (password.isEmpty()) {
                        Log.w(TAG, "[SSH-TEST] PEPPER_SSH_PASSWORD empty - please set in local.properties")
                    }
                    ssh.authPassword("nao", password)

                    ssh.startSession().use { sess ->
                        val cmd = sess.exec("echo ok")
                        cmd.join(5, TimeUnit.SECONDS)
                        val exitStatus = cmd.exitStatus

                        if (exitStatus == null) {
                            Log.w(TAG, "[SSH-TEST] Command timeout")
                        } else {
                            Log.i(TAG, "[SSH-TEST] Connected successfully, exit=$exitStatus")
                            val inputStream = cmd.inputStream
                            val available = inputStream.available()
                            var out = ""
                            if (available > 0) {
                                val buf = ByteArray(minOf(available, 64))
                                val n = inputStream.read(buf)
                                if (n > 0) out = String(buf, 0, n)
                            }
                            Log.i(TAG, "[SSH-TEST] stdout='${out.trim()}'")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[SSH-TEST] Failed: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun createConfig(): net.schmizz.sshj.Config {
        // Configure SSH to avoid Curve25519/X25519 which is missing on older Android crypto providers
        val config = DefaultConfig()
        val kex = config.keyExchangeFactories.toMutableList()
        kex.removeAll { factory ->
            val name = factory.name
            name != null && name.lowercase().contains("curve25519")
        }
        config.keyExchangeFactories = kex
        return config
    }
}
