package com.example.chromacity


import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.chromacity.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


typealias Colour = Triple<Int, Int, Int>
typealias ColourListener = (colour: Colour) -> Unit


class MatchColour : AppCompatActivity() {
    private val permissions = arrayOf("android.permission.CAMERA",
        "android.permission.MANAGE_EXTERNAL_STORAGE")
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var appExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var colourData: Triple<Int, Int, Int>
    private var lightOn = false
    private lateinit var cam: Camera
    private lateinit var file: File
    private lateinit var popupView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        file = File("${filesDir}/colours.txt")
        Log.d("Create file", file.createNewFile().toString())
//        popupWindow = PopupWindow(this)
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, permissions, REQUEST_CODE_PERMISSIONS)
        }

        val textBox = findViewById<EditText>(R.id.text_input)

        viewBinding.imageCaptureButton.setOnClickListener {
            findPaintMatch()
        }

        viewBinding.lightButton.setOnClickListener{
            lightOn = if(lightOn){
                cam.cameraControl.enableTorch(false)
                false
            } else{
                cam.cameraControl.enableTorch(true)
                true
            }
        }

        viewBinding.logColour.setOnClickListener{
            writeToFile(textBox.text.toString() + "," + colourData.toString() +
                    "," + colourData.second.toString() + "," + colourData.third.toString())
        }


//        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        popupView = viewBinding.popupView
        popupView.visibility = View.GONE

        findViewById<Button>(R.id.close_popup).setOnClickListener{
            popupView.visibility = View.GONE
        }

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
//            val numPixels = 100
//            val yValues = yByteArray.slice(IntRange(yCenter-numPixels, yCenter+numPixels)).map {
//                it.toInt() and 255
//            }
//            val uValues = uByteArray.slice(IntRange(uvCenter-numPixels, uvCenter+numPixels)).map {
//                (it.toInt() and 255) - 128
//            }
//            val vValues = vByteArray.slice(IntRange(uvCenter-numPixels, uvCenter+numPixels)).map {
//                (it.toInt() and 255) - 128
//            }
            val yAverage = yByteArray[yCenter].toInt() and 255//yValues.average().roundToInt()
            val uAverage = (uByteArray[uvCenter].toInt() and 255)-128//uValues.average().roundToInt()
            val vAverage = (vByteArray[uvCenter].toInt() and 255)-128//vValues.average().roundToInt()
            val imageData = Triple(yAverage, uAverage, vAverage)

            listener(imageData)

            image.close()
        }
    }

    private fun compareColours(colourOne: Colour, colourTwo: Colour ): Boolean{
        return colourOne.first == colourTwo.first && colourOne.second == colourTwo.second &&
                colourOne.third == colourTwo.third
    }

    private fun findPaintMatch() {
        var matchingColour : Colour
        var matchFound = false
        for(line in file.readLines()){
            val split = line.split(",")
            matchingColour = Triple(split[1].toInt(), split[2].toInt(), split[3].toInt())
            Log.d("split", split.toString())
            if(compareColours(colourData, matchingColour)){
                runOnUiThread{
                    val popupText = findViewById<TextView>(R.id.popup_text)
                    popupText.text = getString(R.string.match_found, split[0])

                }
                matchFound = true
                break
            }
        }
        if (!matchFound) {
            runOnUiThread {
                val popupText = findViewById<TextView>(R.id.popup_text)
                popupText.text = getString(R.string.match_not_found)
            }
        }
        popupView.visibility = View.VISIBLE
//        popupWindow.showAtLocation(findViewById(R.id.viewFinder), Gravity.CENTER, 0, 0)
    }

    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                    it.setAnalyzer(appExecutor, AnalyzeColourData {
                            colour -> runOnUiThread {
                                val dataText = findViewById<TextView>(R.id.dataText)
                                dataText.text = colour.toString()
                                colourData = colour
                            }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cam = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer, imageCapture)

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


    override fun onDestroy() {
        super.onDestroy()
        appExecutor.shutdown()
    }

    private fun writeToFile(data: String) {
        try {
            file.appendText(data + "\n")
            val written = file.readText()
            Log.d("Exception", written)
//            runOnUiThread{
//                val testText = findViewById<TextView>(R.id.testText)
//                testText.text = file.toString()
//            }
//            val writer = file.printWriter()
        } catch (e: IOException) {
            Log.e("Exception", "File write failed: $e")
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
