package io.github.anonymous.pepper_realtime.network;

import android.util.Log;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.Config;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.transport.kex.KeyExchange;
import net.schmizz.sshj.common.Factory;
import java.util.concurrent.TimeUnit;
import java.io.InputStream;

import io.github.anonymous.pepper_realtime.BuildConfig;
import io.github.anonymous.pepper_realtime.manager.ThreadManager;

public class SshConnectionHelper {
    private static final String TAG = "SshConnectionHelper";

    public static void testFixedIpSshConnect(ThreadManager threadManager) {
        threadManager.executeNetwork(() -> {
            String host = "198.18.0.1"; // Known working IP
            
            try (SSHClient ssh = new SSHClient(createConfig())) {
                Log.i(TAG, "[SSH-TEST] Connecting to " + host);
                
                ssh.setConnectTimeout(3000);
                ssh.setTimeout(5000);
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
                ssh.connect(host, 22);
                
                String password = BuildConfig.PEPPER_SSH_PASSWORD != null ? BuildConfig.PEPPER_SSH_PASSWORD : "";
                if (password.isEmpty()) {
                    Log.w(TAG, "[SSH-TEST] PEPPER_SSH_PASSWORD empty - please set in local.properties");
                }
                ssh.authPassword("nao", password);
                
                try (net.schmizz.sshj.connection.channel.direct.Session sess = ssh.startSession()) {
                    Command cmd = sess.exec("echo ok");
                    cmd.join(5, TimeUnit.SECONDS);
                    Integer exitStatus = cmd.getExitStatus();
                    
                    if (exitStatus == null) {
                        Log.w(TAG, "[SSH-TEST] Command timeout");
                    } else {
                        Log.i(TAG, "[SSH-TEST] Connected successfully, exit=" + exitStatus);
                        InputStream is = cmd.getInputStream();
                        int available = is.available();
                        String out = "";
                        if (available > 0) {
                            byte[] buf = new byte[Math.min(available, 64)];
                            int n = is.read(buf);
                            if (n > 0) out = new String(buf, 0, n);
                        }
                        Log.i(TAG, "[SSH-TEST] stdout='" + out.trim() + "'");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "[SSH-TEST] Failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        });
    }
    
    private static Config createConfig() {
        // Configure SSH to avoid Curve25519/X25519 which is missing on older Android crypto providers
        Config config = new DefaultConfig();
        java.util.List<Factory.Named<KeyExchange>> kex = new java.util.ArrayList<>(config.getKeyExchangeFactories());
        java.util.Iterator<Factory.Named<KeyExchange>> it = kex.iterator();
        while (it.hasNext()) {
            Factory.Named<KeyExchange> f = it.next();
            String name = f.getName();
            if (name != null && name.toLowerCase().contains("curve25519")) {
                it.remove();
            }
        }
        config.setKeyExchangeFactories(kex);
        return config;
    }
}
