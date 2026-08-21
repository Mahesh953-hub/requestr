package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.export.FileExporter
import com.example.data.interception.RootType
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Detects the root manager type (Magisk, KernelSU, nAPatch, etc.) and
 * generates a module zip that:
 * 1. Installs requestr's CA to /system/etc/security/cacerts/ on boot
 * 2. Sets up iptables rules for transparent interception
 *
 * The module is saved to the dedicated exports folder for the user to flash
 * in their root manager.
 */
object RootModuleGenerator {

    private const val TAG = "RootModuleGen"

    /** Literal dollar sign for shell scripts to avoid Kotlin template parsing. */
    private const val D = "\$"

    // ── detection ─────────────────────────────────────────────────────────

    fun detectRootType(): RootType {
        return when {
            File("/data/adb/napatch").exists() ||
                File("/data/adb/napatch/modules").exists() -> RootType.NAPATCH
            File("/data/adb/ksu-next").exists() ||
                File("/data/adb/ksu-next/modules").exists() -> RootType.KSU_NEXT
            File("/data/adb/ksu").exists() ||
                File("/data/adb/ksu/modules").exists() -> RootType.KSU
            File("/data/adb/magisk").exists() ||
                File("/data/adb/magisk/modules").exists() -> RootType.MAGISK
            suBinaryExists() -> RootType.GENERIC_SU
            else -> RootType.NONE
        }
    }

    private fun suBinaryExists(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/data/local/su",
        )
        return paths.any { File(it).exists() }
    }

    // ── module generation ─────────────────────────────────────────────────

    fun generateModule(context: Context): String? {
        val rootType = detectRootType()
        if (rootType == RootType.NONE) {
            Log.w(TAG, "No root manager detected — cannot generate module")
            return null
        }

        val caPem = extractCaPem() ?: run {
            Log.w(TAG, "No requestr CA cert found — cannot generate module")
            return null
        }

        val timestamp = java.text.SimpleDateFormat(
            "yyyyMMdd-HHmmss", java.util.Locale.US
        ).format(java.util.Date())

        val moduleName = "requestr-module-$rootType-$timestamp"
        val exportDir = FileExporter.getExportFolder(context)

        if (!exportDir.exists()) exportDir.mkdirs()

        val zipFile = File(exportDir, "$moduleName.zip")

        return runCatching {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                writeModuleProp(zos, rootType, moduleName)
                writeCustomizeScript(zos, rootType)
                writePostFsDataScript(zos)
                writeServiceScript(zos)
                writeCaInstallScript(zos)
                writeEntry(zos, "system/etc/security/cacerts/requestr_ca.crt", caPem.toByteArray())
                writeEntry(zos, "requestr-iptables.rules", buildIptablesRules().toByteArray())
            }

            Log.i(TAG, "Module generated: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            zipFile.absolutePath
        }.getOrElse { e ->
            Log.e(TAG, "Failed to generate module: ${e.message}")
            zipFile.delete()
            null
        }
    }

    // ── module contents ───────────────────────────────────────────────────

    private fun writeModuleProp(zos: ZipOutputStream, rootType: RootType, moduleName: String) {
        val content = listOf(
            "id=$moduleName",
            "name=requestr CA + Interception Module",
            "version=v2.5",
            "versionCode=25",
            "author=requestr",
            "description=Installs requestr CA to system store and configures iptables for transparent HTTPS interception. Generated for $rootType.",
        ).joinToString("\n")
        writeEntry(zos, "module.prop", content.toByteArray())
    }

    private fun writeCustomizeScript(zos: ZipOutputStream, rootType: RootType) {
        val content = listOf(
            "#!/system/bin/sh",
            "# requestr module — customize.sh",
            "# Runs during module installation",
            "",
            "MODDIR=${D}{0%${'/'}*}",
            "MODID=${D}{MODDIR##${'/'}*}",
            "",
            "ui_print \"   requestr CA + Interception Module\"",
            "ui_print \"   Root type: $rootType\"",
            "ui_print \"   Installing to system store...\"",
            "",
            "mkdir -p ${D}MODDIR/system/etc/security/cacerts",
            "cp -f ${D}MODDIR/requestr-ca-install ${D}MODDIR/system/xbin/requestr-ca-install 2>/dev/null",
            "chmod 755 ${D}MODDIR/system/xbin/requestr-ca-install 2>/dev/null",
            "",
            "ui_print \"   \u2713 Module installed. Reboot to activate.\"",
        ).joinToString("\n")
        writeEntry(zos, "customize.sh", content.toByteArray())
    }

    private fun writePostFsDataScript(zos: ZipOutputStream) {
        val content = listOf(
            "#!/system/bin/sh",
            "# requestr module — post-fs-data.sh",
            "# Runs early in boot, before zygote",
            "",
            "MODDIR=${D}{0%${'/'}*}",
            "",
            "CA_DIR=/system/etc/security/cacerts",
            "mkdir -p ${D}CA_DIR 2>/dev/null",
            "cp -f ${D}MODDIR/system/etc/security/cacerts/requestr_ca.crt ${D}CA_DIR/requestr_ca.crt 2>/dev/null",
            "chmod 644 ${D}CA_DIR/requestr_ca.crt 2>/dev/null",
            "chcon u:object_r:system_file:s0 ${D}CA_DIR/requestr_ca.crt 2>/dev/null",
        ).joinToString("\n")
        writeEntry(zos, "post-fs-data.sh", content.toByteArray())
    }

    private fun writeServiceScript(zos: ZipOutputStream) {
        val content = listOf(
            "#!/system/bin/sh",
            "# requestr module — service.sh",
            "# Runs late in boot, after data is mounted",
            "",
            "MODDIR=${D}{0%${'/'}*}",
            "",
            "RULES_FILE=${D}MODDIR/requestr-iptables.rules",
            "if [ -f \"${D}RULES_FILE\" ]; then",
            "    while read -r rule; do",
            "        [ -z \"${D}rule\" ] && continue",
            "        iptables ${D}rule 2>/dev/null",
            "    done < \"${D}RULES_FILE\"",
            "fi",
        ).joinToString("\n")
        writeEntry(zos, "service.sh", content.toByteArray())
    }

    private fun writeCaInstallScript(zos: ZipOutputStream) {
        val content = listOf(
            "#!/system/bin/sh",
            "# Standalone CA install script — can be run manually",
            "CA_SRC=/data/local/tmp/requestr_ca.crt",
            "CA_DST=/system/etc/security/cacerts/requestr_ca.crt",
            "",
            "if [ ! -f \"${D}CA_SRC\" ]; then",
            "    echo \"No CA cert at ${D}CA_SRC\"",
            "    exit 1",
            "fi",
            "",
            "mount -o rw,remount /system",
            "cp -f \"${D}CA_SRC\" \"${D}CA_DST\"",
            "chmod 644 \"${D}CA_DST\"",
            "chown root:root \"${D}CA_DST\"",
            "mount -o ro,remount /system",
            "echo \"CA installed to ${D}CA_DST\"",
        ).joinToString("\n")
        writeEntry(zos, "system/xbin/requestr-ca-install", content.toByteArray())
    }

    private fun buildIptablesRules(): String = listOf(
        "# Redirect HTTP (80) and HTTPS (443) to requestr proxy",
        "-t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to-port 8080",
        "-t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-port 8080",
        "",
        "# Exclude requestr's own traffic (UID-based exclusion handled by VPN)",
    ).joinToString("\n")

    private fun extractCaPem(): String? {
        return runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.contains(CapabilityProbe.CA_ALIAS_PREFIX, ignoreCase = true)) continue
                val cert = ks.getCertificate(alias) as? java.security.cert.X509Certificate ?: continue
                val encoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                val encoded = encoder.encodeToString(cert.encoded)
                return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
            }
            null
        }.getOrElse { e ->
            Log.w(TAG, "Failed to extract CA PEM: ${e.message}")
            null
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
    }
}
