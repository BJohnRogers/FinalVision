package com.example.vision

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.navigation.NavHost
import androidx.navigation.fragment.NavHostFragment
import com.example.vision.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewBinding: ActivityMainBinding // Use of viewbinding
    private var camera: ImageCapture? = null // Camera host

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // Inherits constructor

        enableEdgeToEdge() // Full view of screen

        // Creates the view binding
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Initialises Menu Fragments
        val navHostFrag = supportFragmentManager.findFragmentById(R.id.fragContainer) as NavHostFragment
        val navController = navHostFrag.navController

       // viewBinding.menuButton.setOnClickListener{navController.navigate(R.id.menuFrag)}

        // Gets camera permissions on startup
        if (checkPerms()) {
            openCamera()
        } else { getPerms() }

        viewBinding.photoButton.setOnClickListener{takePhoto()}
        viewBinding.openCamButton.setOnClickListener{viewBinding.camGroup.isVisible = true}
        viewBinding.closeCamButton.setOnClickListener{viewBinding.camGroup.isVisible = false}

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun openCamera(){
        val camLifecycle = ProcessCameraProvider.getInstance(this) // Gets cameraX instance

        camLifecycle.addListener({

            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = camLifecycle.get()

            // Builds the camera preview and attaches it to the UI
            val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            camera = ImageCapture.Builder().build() // Initialises the (previously null) var

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA // Default to back camera

            try {
                // Resets the camera instance
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, camera)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }


     }, ContextCompat.getMainExecutor(this)) // Keeps listeners and calls in order on this thread
    }

    fun takePhoto(){
        val camera = camera ?: return // Null check

        // Settings for file saving
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.UK).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

        // Takes picture using the camera instance
        camera.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo fail: ${exc.message}", exc)
                } // Error case

                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(TAG, "Photo capture success")
                    val rotation = image.imageInfo.rotationDegrees
                    cardImage = proxyToBitmap(image)
                    viewBinding.image.setImageBitmap(cardImage)
                    cardImage?.let { recogniseText(it, rotation) }
                }

            }
        )

    }

    private fun recogniseText(bitmap: Bitmap, rotation: Int){
        val recogniser = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, rotation)

        val result = recogniser.process(image)
            .addOnSuccessListener { visionText ->
               processTextBlock(visionText)

            }
            .addOnFailureListener { e ->
            }
    }

    private fun setCard(uriString: String){
        Log.d("debug","in setcard")
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
    }

    private fun getCard(){
        val url = "https://api.scryfall.com/cards/named?fuzzy=${currentCard}"
        try {
            getData(url)
        } catch(e: IOException){
            return
        }
    }

    private fun getData(url:String){
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call : Call, response: Response){
                response.use{
                    if(!response.isSuccessful) throw IOException("Unexpected code $response")
                    readJSON(response.body!!.string())
                }
            }
        })
    }

    fun readJSON(rawJson:String){
        runOnUiThread(java.lang.Runnable {
            try {
                var json = JSONObject(rawJson)
                var uri = json.getString("scryfall_uri")
                Log.d("debug time", "woo")
                setCard(uri)
            }
            catch (e: JSONException){
                Log.e("JSON", "failed to get the uri", e)
            }
        })
    }

    private fun processTextBlock(result: Text) {
        val resultText = result.text
        for (block in result.textBlocks) {
            val blockText = block.text
            val blockCornerPoints = block.cornerPoints
            val blockFrame = block.boundingBox
            for (line in block.lines) {
                val lineText = line.text
                val lineCornerPoints = line.cornerPoints
                val lineFrame = line.boundingBox
                for (element in line.elements) {
                    val elementText = element.text
                    val elementCornerPoints = element.cornerPoints
                    val elementFrame = element.boundingBox
                }
            }
        }
        currentCard = resultText
        getCard()
        viewBinding.ocrText.text = resultText
    }

    fun proxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.capacity()).also { buffer.get(it) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun getPerms() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun checkPerms() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // GoogleLab value for checking perms
    private val activityResultLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Perm handler
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                openCamera()
            }
        }

    // GoogleLab statics
    companion object {
        private var cardImage: Bitmap ?= null
        private lateinit var currentCard: String
        private const val TAG = "MI Project"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

}