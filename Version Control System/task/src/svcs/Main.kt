package svcs

import org.apache.commons.io.FileUtils
import java.io.File
import java.util.*

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printHelp()
        return
    }
    initVcs()
    handleUserCommands(args)
}

private fun handleUserCommands(args: Array<String>) {
    when (val command = args[0]) {
        "--help" -> printHelp()
        "config" -> handleConfigCommand(args)
        "add" -> handleAddCommand(args)
        "log" -> handleLogCommand()
        "commit" -> handleCommitCommand(args)
        "checkout" -> handleCheckoutCommand(args)
        else -> println("'$command' is not a SVCS command.")
    }
}

private const val VCS = "vcs"
private const val VCS_LOG = "vcs/log.txt"
private const val VCS_INDEX = "vcs/index.txt"
private const val VCS_CONFIG = "vcs/config.txt"
private const val VCS_COMMITS = "vcs/commits"
private const val TRACKED_FILES = "vcs/trackedFiles.txt"

fun handleLogCommand() {
    val commits = getCommits()
    if (commits.isEmpty()) {
        print("No commits yet.")
        return
    }
    commits.sortedByDescending { it.timestamp }.forEachIndexed { index, commit ->
        println(commit.toString())
        if (index != commits.lastIndex) {
            println()
        }
    }
}

fun handleCommitCommand(args: Array<String>) {
    val message = getMessages(args)
    if (message.isEmpty) {
        println("Message was not passed.")
        return
    }

    val (_, trackedFiles) = getTrackedFiles(TRACKED_FILES)
    if (trackedFiles.isEmpty()) {
        println("Nothing to commit.")
        return
    }
    val commitId = randomUUID()

    val commitFolderPath = getCommitFolderPath(commitId)
    createFolder(commitFolderPath)

    val commits = getCommits()
    if (commits.isNotEmpty() && isTrackedFilesChanged(commits.last(), trackedFiles)) {
        println("Nothing to commit.")
        return
    }
    commit(trackedFiles, commitFolderPath, commitId, message)
}

private fun isTrackedFilesChanged(commit: Commit, trackedFiles: List<String>): Boolean =
    trackedFiles.none { !isSame(it, getCommittedFilePath(commit, it)) }

private fun getCommittedFilePath(commit: Commit, it: String) =
    getCommitFolderPath(commit.id) + File.separator + it

private fun commit(
    trackedFiles: List<String>, commitFolderPath: String, commitId: String, message: Optional<String>
) {
    trackedFiles.forEach { File(it).copyTo(File(commitFolderPath + File.separator + it)) }

    val commit = Commit(commitId, message.get(), getConfig().username)
    File(VCS_LOG).appendText(commit.toLine() + "\n")
    println("Changes are committed.")
}

private fun getCommitFolderPath(commitId: String) = VCS_COMMITS + File.separator + commitId


fun isSame(firstFilePath: String, secondFilePath: String): Boolean {
    val file1 = File(firstFilePath)
    val file2 = File(secondFilePath)
    return FileUtils.contentEquals(file1, file2)
}

data class Commit(
    val id: String, val message: String, val author: String, val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String = "commit $id\nAuthor: $author\n$message\n"

    fun toLine(): String = "$id $author $timestamp $message"
}

fun buildCommitFromString(line: String): Commit {
    val parts = line.split(" ")
    val id = parts[0]
    val author = parts[1]
    val timestamp = parts[2].toLong()
    val message = parts.subList(3, parts.lastIndex + 1).joinToString(" ")

    return Commit(id, message, author, timestamp)
}

fun getCommits(): List<Commit> {
    val logFile = File(VCS_LOG)

    return logFile.readText().split("\n").filter { it.isNotEmpty() }.map { buildCommitFromString(it) }
}

fun randomUUID() = UUID.randomUUID().toString()

private fun getMessages(args: Array<String>): Optional<String> {
    if (args.size < 2) {
        return Optional.empty()
    }
    return Optional.of(args.last())
}

fun handleAddCommand(args: Array<String>) {
    val fileToTrack = args.last()

    val (trackedFile, files) = getTrackedFiles(TRACKED_FILES)
    if (args.size == 1) {
        if (files.isEmpty()) {
            println("Add a file to the index.")
            return
        }
        print("Tracked files:\n${files.joinToString("\n")}")
        return
    }
    addTrackFile(fileToTrack, files, trackedFile)
}

fun handleCheckoutCommand(args: Array<String>) {
    if (args.size < 2) {
        println("Commit id was not passed.")
        return
    }
    val commitId = args[1]

    val commitFolder = File(getCommitFolderPath(commitId))
    if (!commitFolder.exists() || commitFolder.isFile) {
        println("Commit does not exist.")
        return
    }
    deleteCurrentFiles()
    copyFilesFromCommit(commitFolder)

    println("Switched to commit $commitId.")
}

private fun copyFilesFromCommit(commitFolder: File) {
    commitFolder.listFiles()?.forEach {
        val target = File(it.name)
        if (it.isDirectory) {
            if (!it.exists()) {
                it.mkdir()
            }
            it.copyRecursively(target, overwrite = true)
        } else if (it.isFile) {
            it.copyTo(target, overwrite = true)
        }
    }
}

private fun deleteCurrentFiles() {
    val (_, files) = getTrackedFiles(TRACKED_FILES)
    var file: File
    files.forEach {
        file = File(it)
        if (file.exists()) {
            if (file.isFile) {
                file.delete()
            } else if (file.isDirectory) {
                file.deleteRecursively()
            }
        }
    }
}

private fun getTrackedFiles(path: String): Pair<File, List<String>> {
    val directoryOfTrackedFiles = File(path)
    if (!directoryOfTrackedFiles.exists()) {
        directoryOfTrackedFiles.createNewFile()
    }
    val filesList = readFilesToList(directoryOfTrackedFiles)
    return Pair(directoryOfTrackedFiles, filesList)
}

private fun addTrackFile(
    fileToTrack: String, files: List<String>, trackedFile: File
) {
    if (!File(fileToTrack).exists()) {
        println("Can't find '$fileToTrack'.")
        return
    }

    if (files.contains(fileToTrack)) {
        println("The file '$fileToTrack' is tracked.")
        return
    }
    val body = getNewTrackedFileString(files, fileToTrack)
    trackedFile.writeText(body)
    println("The file '$fileToTrack' is tracked.")
}

private fun getNewTrackedFileString(
    files: List<String>, fileToTrack: String
): String {
    var text = ""
    if (files.isNotEmpty()) {
        text = files.joinToString("\n") + "\n"
    }
    text += "$fileToTrack\n"
    return text
}

private fun readFilesToList(trackedFile: File) =
    trackedFile.readLines().filter { it.isNotEmpty() && it.isNotEmpty() }

data class Config(var username: String) {
    override fun toString(): String {
        return "username=$username"
    }
}

fun buildConfigFromString(lines: List<String>): Config {
    val config = Config("")
    lines.forEach {
        if (it.isNotEmpty()) {
            val (key, value) = it.split("=")
            when (key) {
                "username" -> config.username = value
            }
        }
    }

    return config
}

fun handleConfigCommand(args: Array<String>) {
    val config = getConfig()

    if (args.size > 1) {
        config.username = args.last()
        writeToFile(VCS_CONFIG, config.toString())
        printUsername(config)
    } else if (config.username.isEmpty()) {
        println("Please, tell me who you are.")
    } else {
        printUsername(config)
    }
}

private fun getConfig(): Config {
    val lines = readFileToLines(VCS_CONFIG)
    return buildConfigFromString(lines)
}

private fun printUsername(config: Config) {
    println("The username is ${config.username}.")
}

fun writeToFile(path: String, body: String) {
    File(path).writeText(body)
}

private fun readFileToLines(path: String): List<String> {
    val configFile = File(path)
    return configFile.readText().split("\n")
}


fun initVcs() {
    createFolder(VCS)
    createFolder(VCS_COMMITS)
    createFile(VCS_CONFIG)
    createFile(VCS_INDEX)
    createFile(VCS_LOG)
    createFile(TRACKED_FILES)
}

private fun createFolder(path: String): File {
    val file = File(path)
    if (!file.exists()) {
        file.mkdir()
    }
    return file
}

private fun createFile(path: String): File {
    val file = File(path)
    if (!file.exists()) {
        file.createNewFile()
    }
    return file
}

private fun printHelp() {
    println(
        "These are SVCS commands:\n" + "config     Get and set a username.\n" + "add        Add a file to the index.\n" + "log        Show commit logs.\n" + "commit     Save changes.\n" + "checkout   Restore a file."
    )
}