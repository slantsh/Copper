package com.slantsh.copper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.URL
import java.net.HttpURLConnection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLDecoder

class CopyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when {
            // Handle direct image sharing
            intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { imageUri ->
                    copyImageToClipboard(imageUri)
                }
            }
            // Handle URL sharing (text/plain for URLs)
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedUrl ->
                    if (isValidUrl(sharedUrl)) {
                        extractAndCopyImageFromUrl(sharedUrl)
                    } else {
                        Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            else -> {
                Toast.makeText(this, "Unsupported content type", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun extractAndCopyImageFromUrl(url: String) {
        // Show loading message
        Toast.makeText(this, "Extracting image...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imageUrl = findImageFromUrl(url)
                if (imageUrl != null) {
                    val bitmap = downloadImage(imageUrl)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            copyBitmapToClipboard(bitmap)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@CopyActivity, "Failed to download image", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CopyActivity, "No image found on this page", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CopyActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private suspend fun findImageFromUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            // First check if the URL itself is a direct image link
            if (isDirectImageUrl(url)) {
                return@withContext url
            }

            // Handle Google Images URLs (both google.com and google.co.uk, etc.)
            if (url.contains("google.") && url.contains("imgurl=")) {
                val imageUrl = extractGoogleImageUrl(url)
                if (imageUrl != null) {
                    return@withContext imageUrl
                }
            }

            // Handle Google Images search result URLs
            if (url.contains("google.") && (url.contains("/imgres?") || url.contains("images?"))) {
                val imageUrl = extractGoogleImageUrl(url)
                if (imageUrl != null) {
                    return@withContext imageUrl
                }
            }

            // Handle Google Photos share links
            if (url.contains("share.google") || url.contains("photos.google.com") || url.contains("photos.app.goo.gl")) {
                val imageUrl = extractGooglePhotosImage(url)
                if (imageUrl != null) {
                    return@withContext imageUrl
                }
            }

            // Generic webpage parsing - works for ANY website
            val doc: Document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(15000)
                .followRedirects(true)
                .get()

            // Try to find image from Open Graph meta tags (used by most social sites)
            var imageUrl = doc.select("meta[property=og:image]").attr("content")
            if (imageUrl.isNotEmpty()) {
                return@withContext resolveUrl(url, imageUrl)
            }

            // Try Twitter card image (used by many sites)
            imageUrl = doc.select("meta[name=twitter:image]").attr("content")
            if (imageUrl.isNotEmpty()) {
                return@withContext resolveUrl(url, imageUrl)
            }

            // Try other common meta image tags
            imageUrl = doc.select("meta[name=image]").attr("content")
            if (imageUrl.isNotEmpty()) {
                return@withContext resolveUrl(url, imageUrl)
            }

            // Try schema.org image markup
            imageUrl = doc.select("meta[itemprop=image]").attr("content")
            if (imageUrl.isNotEmpty()) {
                return@withContext resolveUrl(url, imageUrl)
            }

            // Find the best image on the page
            val bestImage = findBestImageOnPage(doc, url)
            if (bestImage != null) {
                return@withContext bestImage
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findBestImageOnPage(doc: Document, baseUrl: String): String? {
        val images = doc.select("img[src]")
        var bestImage: String? = null
        var bestScore = 0

        for (img in images) {
            val src = img.attr("src")
            if (src.isEmpty() || src.startsWith("data:")) continue

            val fullUrl = resolveUrl(baseUrl, src)
            var score = 0

            // Score based on image characteristics
            val width = img.attr("width").toIntOrNull() ?: 0
            val height = img.attr("height").toIntOrNull() ?: 0
            val alt = img.attr("alt").lowercase()
            val classes = img.attr("class").lowercase()

            // Prefer larger images
            score += (width + height) / 10

            // Prefer images with certain keywords
            if (alt.contains("main") || alt.contains("hero") || alt.contains("featured") ||
                alt.contains("primary") || alt.contains("banner")) score += 100

            if (classes.contains("main") || classes.contains("hero") || classes.contains("featured") ||
                classes.contains("primary") || classes.contains("banner") || classes.contains("thumb")) score += 50

            // Avoid common unwanted images
            if (alt.contains("icon") || alt.contains("logo") || alt.contains("avatar") ||
                classes.contains("icon") || classes.contains("logo") || classes.contains("avatar") ||
                src.contains("icon") || src.contains("logo")) score -= 50

            // Prefer common image formats and domains
            if (src.contains(".jpg") || src.contains(".jpeg") || src.contains(".png") || src.contains(".webp")) score += 20

            // Avoid tiny images
            if (width > 0 && height > 0 && (width < 50 || height < 50)) score -= 100

            if (score > bestScore) {
                bestScore = score
                bestImage = fullUrl
            }
        }

        // If no good scored image, just return the first reasonable one
        if (bestImage == null) {
            for (img in images) {
                val src = img.attr("src")
                if (src.isNotEmpty() && !src.startsWith("data:")) {
                    val fullUrl = resolveUrl(baseUrl, src)
                    if (isLikelyImage(fullUrl)) {
                        return fullUrl
                    }
                }
            }
        }

        return bestImage
    }

    private fun isLikelyImage(url: String): Boolean {
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg")
        return imageExtensions.any { url.lowercase().contains(it) } ||
                url.contains("image") || url.contains("img") || url.contains("photo")
    }

    private fun extractGooglePhotosImage(url: String): String? {
        return try {
            // Google Photos share links need to be parsed by loading the page
            // and looking for the actual image URL in the HTML
            val doc: Document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .get()

            // Look for meta tags that contain the image URL
            var imageUrl = doc.select("meta[property=og:image]").attr("content")
            if (imageUrl.isNotEmpty()) {
                return imageUrl
            }

            // Try Twitter card image
            imageUrl = doc.select("meta[name=twitter:image]").attr("content")
            if (imageUrl.isNotEmpty()) {
                return imageUrl
            }

            // Look for img tags with Google Photos specific patterns
            val images = doc.select("img[src*='googleusercontent.com']")
            if (images.isNotEmpty()) {
                val imgSrc = images.first()?.attr("src")
                if (!imgSrc.isNullOrEmpty()) {
                    // Often Google Photos images have size parameters we can modify
                    return imgSrc.replace("=w\\d+-h\\d+".toRegex(), "=s2048") // Get higher resolution
                        .replace("=s\\d+".toRegex(), "=s2048")
                }
            }

            // Look for any img tag as fallback
            val allImages = doc.select("img[src]")
            if (allImages.isNotEmpty()) {
                for (img in allImages) {
                    val src = img.attr("src")
                    if (src.contains("googleusercontent.com") || src.contains("ggpht.com")) {
                        return src.replace("=w\\d+-h\\d+".toRegex(), "=s2048")
                            .replace("=s\\d+".toRegex(), "=s2048")
                    }
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractGoogleImageUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(url)

            // Try to get the imgurl parameter (direct image URL)
            val imgUrl = uri.getQueryParameter("imgurl")
            if (!imgUrl.isNullOrEmpty()) {
                return@withContext URLDecoder.decode(imgUrl, "UTF-8")
            }

            // Try to get the url parameter (sometimes used)
            val urlParam = uri.getQueryParameter("url")
            if (!urlParam.isNullOrEmpty()) {
                return@withContext URLDecoder.decode(urlParam, "UTF-8")
            }

            // If URL parameters don't work, try parsing the Google Images page itself
            val doc: Document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .get()

            // Look for the main image in Google Images page
            val mainImage = doc.select("img[src*='twimg.com'], img[src*='imgur.com'], img[src*='reddit.com'], img[alt*='Image result']").first()
            if (mainImage != null) {
                val src = mainImage.attr("src")
                if (src.isNotEmpty() && !src.startsWith("data:")) {
                    return@withContext src
                }
            }

            // Look for any external image (not Google's thumbnails)
            val images = doc.select("img[src]")
            for (img in images) {
                val src = img.attr("src")
                if (src.contains("twimg.com") || src.contains("imgur.com") ||
                    src.contains("reddit.com") || src.contains("githubusercontent.com") ||
                    (src.startsWith("http") && !src.contains("google") && !src.contains("gstatic"))) {
                    return@withContext src
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isDirectImageUrl(url: String): Boolean {
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
        return imageExtensions.any { url.lowercase().contains(it) }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return if (relativeUrl.startsWith("http")) {
            relativeUrl
        } else {
            val base = Uri.parse(baseUrl)
            Uri.Builder()
                .scheme(base.scheme)
                .authority(base.authority)
                .path(if (relativeUrl.startsWith("/")) relativeUrl else "/${relativeUrl}")
                .build()
                .toString()
        }
    }

    private suspend fun downloadImage(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection

            // Set comprehensive headers to work with any website
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            connection.setRequestProperty("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br")
            connection.setRequestProperty("Connection", "keep-alive")
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1")
            connection.setRequestProperty("Sec-Fetch-Dest", "image")
            connection.setRequestProperty("Sec-Fetch-Mode", "no-cors")
            connection.setRequestProperty("Sec-Fetch-Site", "cross-site")

            // Set referer based on the image URL domain to avoid blocking
            val imageHost = URL(imageUrl).host
            connection.setRequestProperty("Referer", "https://$imageHost/")

            // Handle redirects and timeouts
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doInput = true

            val responseCode = connection.responseCode

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val inputStream = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    connection.disconnect()
                    return@withContext bitmap
                }
                HttpURLConnection.HTTP_FORBIDDEN, HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    connection.disconnect()
                    // Try with different approach for protected images
                    return@withContext downloadProtectedImage(imageUrl)
                }
                else -> {
                    connection.disconnect()
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            // Try alternative download method as fallback
            return@withContext downloadWithFallback(imageUrl)
        }
    }

    private suspend fun downloadProtectedImage(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection

            // Try with minimal headers for sites that block based on headers
            connection.setRequestProperty("User-Agent", "curl/7.68.0")
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                return@withContext bitmap
            }

            connection.disconnect()
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun downloadWithFallback(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Last resort - try with basic connection
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()

            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            connection.disconnect()
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun copyImageToClipboard(imageUri: Uri) {
        try {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newUri(contentResolver, "Copied Image", imageUri)
            clipboardManager.setPrimaryClip(clipData)

            Toast.makeText(this, "Image copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to copy image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun copyBitmapToClipboard(bitmap: Bitmap) {
        try {
            // Save bitmap to cache and get URI
            val uri = saveBitmapToCache(bitmap)
            if (uri != null) {
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newUri(contentResolver, "Extracted Image", uri)
                clipboardManager.setPrimaryClip(clipData)

                Toast.makeText(this, "Image extracted and copied!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to copy image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun saveBitmapToCache(bitmap: Bitmap): Uri? {
        return try {
            val file = File(cacheDir, "extracted_image_${System.currentTimeMillis()}.png")
            val outputStream = file.outputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.close()

            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }
}