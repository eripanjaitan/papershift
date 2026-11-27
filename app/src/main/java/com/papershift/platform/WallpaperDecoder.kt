package com.papershift.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import com.papershift.core.CropMath
import com.papershift.core.DecodeFailure
import com.papershift.core.DecodedImage
import com.papershift.core.ImageDecoderPort
import com.papershift.core.ImageInput
import com.papershift.core.PixelSize
import java.io.IOException
import java.nio.ByteBuffer

class BitmapImage(val bitmap: Bitmap) : DecodedImage {
    override fun release() = bitmap.recycle()
}

/**
 * Single-step decode (PRD §4.2): ImageDecoder scales so the image covers the target and
 * crops to it in one pass, so no second full-size bitmap is ever allocated. EXIF
 * orientation is applied by ImageDecoder. No OnPartialImageListener is set, so truncated
 * data throws instead of producing a half-grey image (EC-06).
 */
class WallpaperDecoder(context: Context) : ImageDecoderPort {
    private val resolver = context.applicationContext.contentResolver

    override fun decode(input: ImageInput, target: PixelSize): DecodedImage {
        val source = when (input) {
            is ImageInput.Content -> ImageDecoder.createSource(resolver, Uri.parse(input.uri))
            is ImageInput.Bytes -> ImageDecoder.createSource(ByteBuffer.wrap(input.bytes))
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = PixelSize(info.size.width, info.size.height)
                if (!CropMath.isLargeEnough(size)) throw DecodeFailure("Too small: $size")
                val plan = CropMath.plan(size, target)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // WallpaperManager compresses the bitmap.
                decoder.setTargetSize(plan.scaledWidth, plan.scaledHeight)
                decoder.crop = Rect(plan.left, plan.top, plan.right, plan.bottom)
            }
        } catch (e: DecodeFailure) {
            throw e
        } catch (e: IOException) {
            // Includes ImageDecoder.DecodeException (corrupt, unsupported or truncated data).
            throw DecodeFailure("Decode failed", e)
        } catch (e: SecurityException) {
            throw DecodeFailure("No access to image", e)
        } catch (e: IllegalArgumentException) {
            throw DecodeFailure("Invalid image", e)
        } catch (e: OutOfMemoryError) {
            throw DecodeFailure("Out of memory decoding image", e)
        }
        return BitmapImage(bitmap)
    }
}
