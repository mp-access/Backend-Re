package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.TaskFileDTO
import org.apache.commons.compress.utils.FileNameUtils
import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MimeTypes
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import org.apache.commons.codec.binary.Base64
import org.apache.tika.config.TikaConfig

class FileData(
    var path: String? = null,
    var name: String? = null,
    var mimeType: String? = null,
    var content: String? = null,
    var contentBinary: ByteArray? = null,
) {

    fun isBinary(): Boolean {
        return contentBinary != null
    }

    fun validated(): FileData {
        if (path != null &&
            name != null &&
            mimeType != null &&
            ((content == null) xor (contentBinary == null))
        ) return this
        else throw InvalidFileException()
    }

}

class InvalidFileException : Throwable() {

}

// Abstracts away much of the hassle of dealing with various file types
@Service
class FileService(val tika: Tika) {

    fun storeFile(path: Path): FileData {
        val fileData = FileData()
        val bytes = BufferedInputStream(Files.newInputStream(path))
        val metadata = Metadata()
        val detector = TikaConfig.getDefaultConfig().detector
        val mimeType = detector.detect(bytes, metadata)
        fileData.path = path.toString()
        fileData.name = path.fileName.toString()
        fileData.mimeType = mimeType.toString()
        // first, we trust the file extension, if we can recognize it
        val extension = FileNameUtils.getExtension(path.toString())
        if (listOf("py", "r").contains(extension.lowercase(Locale.getDefault()))) {
            fileData.content = Files.readString(path)
            return fileData.validated()
        }
        // if the mimeType is text, that's what we assume
        if (mimeType.type == "text") {
            fileData.content = Files.readString(path)
            return fileData.validated()
        }
        // otherwise we store it as binary anyway
        fileData.contentBinary = Files.readAllBytes(path)
        return fileData.validated()
    }

    fun storeFile(path: Path, dto: TaskFileDTO): TaskFileDTO {
        val fileData = storeFile(path)
        dto.template = fileData.content
        dto.templateBinary = fileData.contentBinary
        dto.path = fileData.path
        dto.mimeType = fileData.mimeType
        return dto
    }

    fun readToBase64(path: Path): String {
        val fileType = tika.detect(path)
        return "data:${fileType};base64," + Base64.encodeBase64String(Files.readAllBytes(path))
    }
}
