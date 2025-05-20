package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.GlobalFile
import ch.uzh.ifi.access.model.ResultFile
import ch.uzh.ifi.access.model.TaskFile
import org.apache.commons.codec.binary.Base64
import org.apache.commons.compress.utils.FileNameUtils
import org.apache.tika.Tika
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class FileData(
    var path: String? = null,
    var name: String? = null,
    var mimeType: String? = null,
    var content: String? = null,
    var contentBinary: ByteArray? = null,
) {

    fun validated(): FileData {
        if (path != null &&
            name != null &&
            mimeType != null &&
            ((content == null) xor (contentBinary == null))
        ) return this
        else throw InvalidFileException()
    }

}

class InvalidFileException : Throwable()

// Abstracts away much of the hassle of dealing with various file types
@Service
class FileService(val tika: Tika) {

    fun storeFile(path: Path): FileData {
        val fileData = FileData()
        fileData.path = path.toString()
        fileData.name = path.fileName.toString()
        // because Tika actually cannot detect python3 scripts, we trust the file extension for now
        val extension = FileNameUtils.getExtension(path.toString()).lowercase(Locale.getDefault())
        if (listOf("py", "r", "sh", "bash").contains(extension)) {
            fileData.mimeType = when (extension) {
                "py" -> "text/x-python"
                "r" -> "text/plain"
                else -> "text/x-sh"
            }
            fileData.content = Files.readString(path)
            return fileData.validated()
        }
        // otherwise we use Tika
        val mimeType = tika.detect(path)
        fileData.mimeType = mimeType.toString()
        // if the mimeType is text, we store as text
        if (mimeType.startsWith("text/")) {
            fileData.content = Files.readString(path)
            return fileData.validated()
        }
        // otherwise we store it as binary
        fileData.contentBinary = Files.readAllBytes(path)
        return fileData.validated()
    }

    fun storeFile(path: Path, taskFile: TaskFile): TaskFile {
        val fileData = storeFile(path)
        taskFile.template = fileData.content
        taskFile.templateBinary = fileData.contentBinary
        taskFile.path = fileData.path
        taskFile.mimeType = fileData.mimeType
        return taskFile
    }

    fun storeFile(path: Path, globalFile: GlobalFile): GlobalFile {
        val fileData = storeFile(path)
        globalFile.template = fileData.content
        globalFile.templateBinary = fileData.contentBinary
        globalFile.path = fileData.path
        globalFile.mimeType = fileData.mimeType
        return globalFile
    }

    fun storeFile(path: Path, resultFile: ResultFile): ResultFile {
        val fileData = storeFile(path)
        resultFile.content = fileData.content
        resultFile.contentBinary = fileData.contentBinary
        resultFile.mimeType = fileData.mimeType
        return resultFile
    }

    fun readToBase64(path: Path): String {
        val fileType = tika.detect(path)
        return "data:${fileType};base64," + Base64.encodeBase64String(Files.readAllBytes(path))
    }
}
