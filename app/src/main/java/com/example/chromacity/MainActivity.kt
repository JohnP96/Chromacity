package com.example.chromacity


import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.chromacity.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt


typealias ColourListener = (colour: Triple<Int, Int, Int>) -> Unit

class MainActivity : AppCompatActivity() {
    private val permissions = arrayOf("android.permission.CAMERA")
    private lateinit var viewBinding: ActivityMainBinding
//    private lateinit var sensorManager: SensorManager
    private lateinit var appExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (! Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, permissions, REQUEST_CODE_PERMISSIONS)
        }

//        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
//        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
//        sensorManager.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }

        appExecutor = Executors.newSingleThreadExecutor()
    }

    private class AnalyzeColourData(private val listener: ColourListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }

        override fun analyze(image: ImageProxy) {
            val planes = image.planes
            val height = image.height
            val width = image.width

            val yByteArray = planes[0].buffer.toByteArray()
            val uByteArray = planes[1].buffer.toByteArray()
            val vByteArray = planes[2].buffer.toByteArray()

            // Get the pixel values for the center of the image
            val yCenter = (height * planes[0].rowStride + width * planes[0].pixelStride) / 2
            val uvCenter = (height * planes[1].rowStride + width * planes[1].pixelStride) / 4

            // Set a 200px square for the capture in the center of the screen
            val numPixels = 100
            val yValues = yByteArray.slice(IntRange(yCenter-numPixels, yCenter+numPixels)).map {
                it.toInt() and 255
            }
            val uValues = uByteArray.slice(IntRange(uvCenter-numPixels, uvCenter+numPixels)).map {
                (it.toInt() and 255) - 128
            }
            val vValues = vByteArray.slice(IntRange(uvCenter-numPixels, uvCenter+numPixels)).map {
                (it.toInt() and 255) - 128
            }
            val yAverage = yByteArray[yCenter].toInt() and 255//yValues.average().roundToInt()
            val uAverage = (uByteArray[uvCenter].toInt() and 255)-128//uValues.average().roundToInt()
            val vAverage = (vByteArray[uvCenter].toInt() and 255)-128//vValues.average().roundToInt()
            val imageData = Triple(yAverage, uAverage, vAverage)

            listener(imageData)

            image.close()
        }
    }

    private fun findPaintMatch() {}

    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                    it.setAnalyzer(appExecutor, AnalyzeColourData() {
                            colour -> runOnUiThread {
                                val dataText = findViewById<TextView>(R.id.dataText)
                                dataText.text = colour.toString()
                            }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                val cam = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer, imageCapture)

                if (cam.cameraInfo.hasFlashUnit()){
                    cam.cameraControl.enableTorch(true)
                }
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = 
        (ContextCompat.checkSelfPermission(baseContext, permissions[0]) ==
                PackageManager.PERMISSION_GRANTED)


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

//    private val lightListener: SensorEventListener = object : SensorEventListener {
//        override fun onSensorChanged(event: SensorEvent) {
//            val lightValue = event.values[0]
//            runOnUiThread {
//                val testText = findViewById<TextView>(R.id.testText)
//                testText.text = lightValue.toString()
//            }
//        }
//        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
//    }

    override fun onDestroy() {
        super.onDestroy()
//        sensorManager.unregisterListener(lightListener)
        appExecutor.shutdown()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.UK)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

//    class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
//        private val paint = Paint()
//        private val targets: MutableList<Rect> = ArrayList()
//
//        override fun onDraw(canvas: Canvas) {
//            super.onDraw(canvas)
//
//            synchronized(this) {
//                for (entry in targets) {
//                    canvas.drawRect(entry, paint)
//                }
//            }
//        }
//
//        fun setTargets(sources: List<Rect>) {
//            synchronized(this) {
//                targets.clear()
//                targets.addAll(sources)
//                this.postInvalidate()
//            }
//        }
//
//        init {
//            val density = context.resources.displayMetrics.density
//            paint.strokeWidth = 2.0f * density
//            paint.color = Color.BLUE
//            paint.style = Paint.Style.STROKE
//        }
//    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
