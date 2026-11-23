package dev.hiorcraft.openssh.ssh.testing

import java.nio.file.Files
import java.nio.file.Path

/**
 * Startet den Test-SSH-Server für manuelle Tests der App:
 * `./gradlew :core-ssh:runTestServer` → 127.0.0.1:2222, Benutzer `tester`, Passwort `s3cret!`.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 2222
    val root = Files.createTempDirectory("openssh-demo-sftp")
    Files.createDirectories(root.resolve("projekt/src"))
    Files.writeString(root.resolve("README.txt"), "Demo-Dateien des Test-Servers\n")
    Files.writeString(root.resolve("projekt/src/main.kt"), "fun main() = println(\"Hallo\")\n")
    val hostKey = Path.of(System.getProperty("java.io.tmpdir"), "openssh-demo-hostkey.ser")
    TestSshServer(root, hostKey).start(port)
    println("Test-SSH-Server läuft auf 127.0.0.1:$port (Benutzer ${TestSshServer.USER}, Passwort ${TestSshServer.PASSWORD}), SFTP-Wurzel: $root")
    Thread.currentThread().join()
}
