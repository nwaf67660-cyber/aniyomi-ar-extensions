apply(from = "repositories.gradle.kts")

include(":core")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Load all modules under /lib-multisrc
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

if (System.getenv("CI") != "true") {
    loadAllIndividualExtensions()
} else {
    val chunkSize = System.getenv("CI_CHUNK_SIZE").toInt()
    val chunk = System.getenv("CI_CHUNK_NUM").toInt()

    File(rootDir, "src").getChunk(chunk, chunkSize)?.forEach {
        loadIndividualExtension(it.parentFile.name, it.name)
    }
}

fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            loadIndividualExtension(dir.name, subdir.name)
        }
    }
}

fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.getChunk(chunk: Int, chunkSize: Int): List<File>? {
    return listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir -> dir.listFiles()?.filter { it.isDirectory } }
        ?.flatten()
        ?.sortedBy { it.name }
        ?.chunked(chunkSize)
        ?.get(chunk)
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
