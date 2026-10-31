package com.pyvideotrans.subtitle.data.asr

import android.content.Context
import android.system.Os
import java.io.File

/**
 * FastRPC userspace library locations. QNN's HTP stub links against
 * `libcdsprpc.so`, which lives on the vendor or system partition and is not part
 * of an application's linker namespace search path, so the bare-name lookup fails
 * even on devices where the library is installed.
 */
private val CDSPRPC_CANDIDATES = listOf(
    "/vendor/lib64/",
    "/apex/com.android.vndk.v34/lib64/",
    "/apex/com.android.vndk.v33/lib64/",
    "/apex/com.android.vndk.v31/lib64/",
    "/system/lib64/",
    "/system_ext/lib64/",
    "/odm/lib64/",
    "/product/lib64/",
)

private const val CDSPRPC_NAME = "libcdsprpc.so"

internal fun cdspRpcDir(): String? =
    CDSPRPC_CANDIDATES.firstOrNull { File(it, CDSPRPC_NAME).isFile }

/** Every location that holds the library; loading one of these by full path is what makes the bare name resolvable. */
internal fun cdspRpcPaths(): List<String> =
    CDSPRPC_CANDIDATES.filter { File(it, CDSPRPC_NAME).isFile }.map { "$it$CDSPRPC_NAME" }

/**
 * Paths worth asking a command-line process about. When the app can see the
 * library nowhere, the first few standard locations still tell us whether it is
 * missing entirely or merely hidden from the app namespace.
 */
internal fun cdspProbeTargets(): List<String> =
    cdspRpcPaths().ifEmpty { CDSPRPC_CANDIDATES.take(3).map { "$it$CDSPRPC_NAME" } }

/**
 * Search paths that contain the library, plus paths that hold some other file.
 * A directory without `libcdsprpc.so` must not be handed to `LD_LIBRARY_PATH`
 * ahead of a populated one: the loader caches the first namespace that resolves
 * the soname, so an empty hit would permanently break later lookups.
 */
internal fun cdspLibraryPaths(nativeLibDir: File): Pair<String, String> {
    val usable = mutableListOf<String>()
    val missing = mutableListOf<String>()
    if (nativeLibDir.isDirectory) {
        usable += nativeLibDir.absolutePath
    }
    CDSPRPC_CANDIDATES.forEach { dir ->
        when {
            File(dir, CDSPRPC_NAME).isFile -> usable += dir
            File(dir).isDirectory -> missing += dir
        }
    }
    return usable.joinToString(File.pathSeparator) to missing.joinToString(File.pathSeparator)
}

/**
 * The app itself may not load a vendor library, so the only trustworthy verdict
 * comes from a plain command-line process. This is what decides whether routing
 * NPU work through `app_process` is possible at all.
 */
internal fun cdspProbeReport(context: Context, nativeLibDir: File): String {
    val (usable, missing) = cdspLibraryPaths(nativeLibDir)
    val hit = cdspRpcDir()
    val kernel = runCatching { Os.uname().release }.getOrDefault("?")
    val loaderPath = System.getenv("LD_LIBRARY_PATH").orEmpty()
    return buildString {
        append("cdsprpc 命中=${hit ?: "无"} 可用目录=[${usable.ifBlank { "无" }}] ")
        append("存在但缺库=[${missing.ifBlank { "无" }}] ")
        append("kernel=$kernel 本进程 LD_LIBRARY_PATH=${loaderPath.ifBlank { "(未设置)" }} ")
        append("数据目录=${context.applicationInfo.dataDir}")
    }
}
