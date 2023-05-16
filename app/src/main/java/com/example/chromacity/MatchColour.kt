package com.example.chromacity


import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt


typealias Colour = Triple<Int, Int, Int>
typealias ColourData = Triple<Colour, Colour, String>
typealias ColourListener = (colour: ColourData) -> Unit


class MatchColour : AppCompatActivity() {
    private val permissions = arrayOf("android.permission.CAMERA",
        "android.permission.MANAGE_EXTERNAL_STORAGE")
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var appExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var colourData: ColourData
    private var lightOn = false
    private lateinit var cam: Camera
    private lateinit var file: File
    private lateinit var popupView: View
    private lateinit var textBox: EditText

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        file = File("${filesDir}/colours.txt")
        Log.d("Create file", file.createNewFile().toString())
        colourData = ColourData(Colour(0,0,0),
            Colour(0,0,0), "")
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, permissions, REQUEST_CODE_PERMISSIONS)
        }

        textBox = findViewById(R.id.text_input)

        viewBinding.imageCaptureButton.setOnClickListener {
            if(file.readLines().isNotEmpty()){
                findPaintMatch()
            }
            else{
                runOnUiThread {
                    val popupText = findViewById<TextView>(R.id.popup_text)
                    popupText.text = getString(R.string.no_colours_logged)
                    popupView.visibility = View.VISIBLE
                }
            }
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
            val name = textBox.text.toString()
            Log.d("Textbox", name)
            var duplicateName = false
            if(name != "") {
                for(line in file.readLines()){
                    if (line.split(",")[0] == name){
                        duplicateName = true
                        break
                    }
                }
                if (!duplicateName) {
                    /*
                    Colour data is written to the file in the format: name,y,u,v,r,g,b,hex
                     */
                    writeToFile(
                        name + "," + colourData.first.first.toString() +
                                "," + colourData.first.second.toString() + "," +
                                colourData.first.third.toString() + "," +
                                colourData.second.first.toString() + "," +
                                colourData.second.second.toString() + "," +
                                colourData.second.third.toString() + "," + colourData.third
                    )
                    runOnUiThread {
                        val popupText = findViewById<TextView>(R.id.popup_text)
                        popupText.text = getString(R.string.colour_logged)
                    }
                }
                else{
                    runOnUiThread {
                        val popupText = findViewById<TextView>(R.id.popup_text)
                        popupText.text = getString(R.string.duplicate_colour_name)
                    }
                }
            }
            else{
                runOnUiThread {
                    val popupText = findViewById<TextView>(R.id.popup_text)
                    popupText.text = getString(R.string.missing_colour_name)
                }
            }
            popupView.visibility = View.VISIBLE
        }


//        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        popupView = viewBinding.popupView
        popupView.visibility = View.GONE

        findViewById<Button>(R.id.close_popup).setOnClickListener{
            popupView.visibility = View.GONE
        }

        val previewView = viewBinding.viewFinder
        previewView.setOnTouchListener(View.OnTouchListener {
                _: View, motionEvent: MotionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> return@OnTouchListener true
                MotionEvent.ACTION_UP -> {
                    // Get the MeteringPointFactory from PreviewView
                    val factory = previewView.meteringPointFactory

                    val point = factory.createPoint(motionEvent.x, motionEvent.y)

                    val action = FocusMeteringAction.Builder(point).build()

                    cam.cameraControl.startFocusAndMetering(action)

                    return@OnTouchListener true
                }

                else -> return@OnTouchListener false
            }
        })

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

            // Set a 20px square for the capture in the center of the screen
            val numPixels = 10
            val yValues = yByteArray.slice(IntRange(yCenter-numPixels, yCenter+numPixels)).map {
                it.toInt() and 255
            }
            val uValues = uByteArray.slice(IntRange(uvCenter-numPixels, uvCenter+numPixels)).map {
                (it.toInt() and 255) - 128
            }
            val vValues = vByteArray.slice(IntRange(uvCenter-numPixels, uvCenter+numPixels)).map {
                (it.toInt() and 255) - 128
            }
            val y = yValues.average().roundToInt() //yByteArray[yCenter].toInt() and 255
            val u = uValues.average().roundToInt() //(uByteArray[uvCenter].toInt() and 255)-128
            val v = vValues.average().roundToInt() //(vByteArray[uvCenter].toInt() and 255)-128

            val r = (y + 1.14*v).roundToInt()
            val g = (y - 0.395*u - 0.581*v).roundToInt()
            val b = (y + 2.032*u).roundToInt()

            val hex = (r.toString(16) + g.toString(16) + b.toString(16)).uppercase()

            val imageData = Triple(Triple(y, u, v), Triple(r,g,b), hex)

            listener(imageData)

            image.close()
        }
    }

    private fun compareColours(colourOne: Colour, colourTwo: Colour ): Pair<Boolean, Double>{
        Log.d("CompareColours", "One: $colourOne Two: $colourTwo")
        val yOne = colourOne.first.toDouble()
        val uOne = colourOne.second.toDouble()
        val vOne = colourOne.third.toDouble()
        val yTwo = colourTwo.first.toDouble()
        val uTwo = colourTwo.second.toDouble()
        val vTwo = colourTwo.third.toDouble()
        val yDiff = (yOne - yTwo).pow(2)
        val uDiff = (uOne - uTwo).pow(2)
        val vDiff = (vOne - vTwo).pow(2)
        Log.d("CompareColours", "y: $yDiff u: $uDiff v: $vDiff")
        val eucDist = sqrt(yDiff + uDiff + vDiff)
        val comparison = sqrt(yDiff) < 20 && sqrt(uDiff) < 2 && sqrt(vDiff) < 2
        Log.d("CompareColours", comparison.toString())
        return Pair(comparison, eucDist)
    }

    private fun findPaintMatch() {
        var matchingColour : Colour
        var matchFound = false
        var closestMatch = Pair(listOf(""), Double.MAX_VALUE)
        val lines = file.readLines()
        var comparison: Pair<Boolean, Double>
        for(line in lines){
            val split = line.split(",")
            matchingColour = Colour(split[1].toInt(), split[2].toInt(), split[3].toInt())
            Log.d("split", split.toString())
            comparison = compareColours(colourData.first, matchingColour)
            Log.d("ClosestMatch", comparison.second.toString())
            if (closestMatch.second > comparison.second){
                closestMatch = Pair(split, comparison.second)
                Log.d("ClosestMatch", closestMatch.toString())
            }
            if(comparison.first){
                matchFound = true
            }
        }
        if (!matchFound) {
            if (closestMatch.second < 25) {
                runOnUiThread {
                    val col = getColourFromFile(closestMatch.first)
                    val popupText = findViewById<TextView>(R.id.popup_text)
                    popupText.text = getString(R.string.closest_match_found, col.first,
                        col.second.first, col.second.second, col.second.third)
                }
            }
            else{
                runOnUiThread {
                    val popupText = findViewById<TextView>(R.id.popup_text)
                    popupText.text = getString(R.string.match_not_found)
                }
            }
        }
        else{
            runOnUiThread{
                val col = getColourFromFile(closestMatch.first)

                val popupText = findViewById<TextView>(R.id.popup_text)
                popupText.text = getString(R.string.match_found, col.first,
                    col.second.first, col.second.second, col.second.third)
            }
        }
        popupView.visibility = View.VISIBLE
//        popupWindow.showAtLocation(findViewById(R.id.viewFinder), Gravity.CENTER, 0, 0)
    }

    private fun getColourFromFile(split: List<String>): Pair<String, ColourData>{
        val yuv = Colour(split[1].toInt(), split[2].toInt(), split[3].toInt())
        val rgb = Colour(split[4].toInt(), split[5].toInt(), split[6].toInt())
        val hex = split[7]
        return Pair(split[0],ColourData(yuv, rgb, hex))
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
                    it.setAnalyzer(appExecutor, AnalyzeColourData { colour ->
                        runOnUiThread {
                            val yuvText = findViewById<TextView>(R.id.yuvText)
                            val rgbText = findViewById<TextView>(R.id.rgbText)
                            val hexText = findViewById<TextView>(R.id.hexText)
                            yuvText.text = getString(R.string.yuv,colour.first.toString())
                            rgbText.text = getString(R.string.rgb,colour.second.toString())
                            hexText.text = getString(R.string.hex,colour.third)
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
            Log.d("Write", written)
        } catch (e: IOException) {
            Log.e("Write", "File write failed: $e")
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
