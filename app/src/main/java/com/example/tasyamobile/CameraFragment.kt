package com.example.tasyamobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.tasyamobile.databinding.FragmentCameraBinding
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


@ExperimentalGetImage
class CameraFragment : Fragment() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var client: OkHttpClient
    private lateinit var binding: FragmentCameraBinding
    private lateinit var socket: Socket

    private var isCameraEnabled = false
    private var isCameraReady = false
    private var isCameraCooldown = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cameraPreviewContainer: FrameLayout = view.findViewById(R.id.cameraPreviewContainer)
        cameraPreviewContainer.post {
            val previewWidth = cameraPreviewContainer.width
            val previewHeight = cameraPreviewContainer.height
            setAspectRatio(previewWidth, previewHeight)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        client = OkHttpClient()
        socket = IO.socket("http://192.168.1.7:5000") // Replace with your Flask server IP
        socket.connect()


        if (!hasCameraPermission()) {
            requestCameraPermission()
        }

        binding.fabToggleCamera.setOnClickListener {
            toggleCamera()
        }
    }

    private fun hasCameraPermission(): Boolean {
        val permission = Manifest.permission.CAMERA
        val result = ContextCompat.checkSelfPermission(requireContext(), permission)
        return result == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        val permission = Manifest.permission.CAMERA
        val requestCode = 123
        requestPermissions(arrayOf(permission), requestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 123 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCodeExecution()
        } else {
            Log.e(TAG, "Camera permission denied")
            // Handle permission denied case
        }
    }

    private fun setAspectRatio(previewWidth: Int, previewHeight: Int) {
        val containerWidth = binding.cameraPreviewContainer.width
        val containerHeight = binding.cameraPreviewContainer.height

        val previewRatio = previewWidth.toFloat() / previewHeight.toFloat()
        val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()

        val layoutParams = binding.processedFrameImageView.layoutParams as ViewGroup.LayoutParams

        if (previewRatio > containerRatio) {
            layoutParams.width = containerWidth
            layoutParams.height = (containerWidth / previewRatio).toInt()
        } else {
            layoutParams.width = (containerHeight * previewRatio).toInt()
            layoutParams.height = containerHeight
        }

        binding.processedFrameImageView.layoutParams = layoutParams
    }



    private fun startCodeExecution() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        val containerWidth = binding.cameraPreviewContainer.width
        val containerHeight = binding.cameraPreviewContainer.height

        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.rotateAndConvertToBitmap()
                        bitmap?.let {
                            sendFrameToServer(bitmap, containerWidth, containerHeight) // Pass the width and height here
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    imageAnalyzer
                )
                isCameraEnabled = true
                binding.fabToggleCamera.setImageResource(R.drawable.ic_stop)
                binding.turnOnCameraTextView.visibility = View.GONE
                binding.processedFrameImageView.visibility = View.VISIBLE
                startVideoStreaming()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera provider: ${e.message}")
            }

            socket.on("processed_frame") { args ->
                val data = args[0] as JSONObject
                val frame = data.getString("frame")

                // Process the received frame data
                val decodedFrameData = Base64.decode(frame, Base64.DEFAULT)
                processVideoFrame(decodedFrameData)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCodeExecution() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            stopVideoStreaming()
            isCameraEnabled = false
            binding.fabToggleCamera.setImageResource(R.drawable.ic_play)
            binding.turnOnCameraTextView.visibility = View.VISIBLE
            binding.processedFrameImageView.visibility = View.GONE

            isCameraCooldown = true
            Handler().postDelayed({
                isCameraCooldown = false
            }, 1000)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun toggleCamera() {
        if (isCameraEnabled && !isCameraCooldown) {
            stopCodeExecution()
            isCameraReady = false
        } else if (!isCameraCooldown) {
            startCodeExecution()
        }
    }

    private fun sendFrameToServer(bitmap: Bitmap, width: Int, height: Int) {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val frameData = outputStream.toByteArray()

        val eventData = JSONObject()
        eventData.put("frame", Base64.encodeToString(frameData, Base64.DEFAULT))
        eventData.put("width", width)
        eventData.put("height", height)

        socket.emit("video_stream", eventData)
    }


    private fun processVideoFrame(frameData: ByteArray) {
        // Process the video frame here
        // You can perform any necessary operations on the frame data

        // Example: Logging the size of the received frame
        Log.d(TAG, "Received frame with size: ${frameData.size} bytes")

        // Example: Convert the frame data to a bitmap
        val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)

        // Example: Update the UI with the processed frame
        activity?.runOnUiThread {
            binding.processedFrameImageView.setImageBitmap(bitmap)
        }
    }


    private fun startVideoStreaming() {
        val frameData = ByteArray(0) // Replace with the actual frame data
        val eventData = JSONObject()
        eventData.put("frame", Base64.encodeToString(frameData, Base64.DEFAULT))
        socket.emit("video_stream", eventData)
    }



    private fun stopVideoStreaming() {
        client.dispatcher.cancelAll() // Cancels all ongoing requests in the client
    }


    private fun ImageProxy.rotateAndConvertToBitmap(): Bitmap? {
        val imageRotationDegrees = imageInfo.rotationDegrees
        val imageWidth = planes[0].rowStride / planes[0].pixelStride
        val imageHeight = planes[0].buffer.remaining() / planes[0].rowStride * planes[0].pixelStride

        val yBuffer: ByteBuffer = planes[0].buffer
        val uBuffer: ByteBuffer = planes[1].buffer
        val vBuffer: ByteBuffer = planes[2].buffer

        val ySize: Int = yBuffer.remaining()
        val uSize: Int = uBuffer.remaining()
        val vSize: Int = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageWidth, imageHeight, null)

        val outputStream = ByteArrayOutputStream()
        val rect = Rect(0, 0, imageWidth, imageHeight)
        val rotationMatrix = Matrix()
        rotationMatrix.postRotate(imageRotationDegrees.toFloat())

        // Rotate and convert the image to JPEG
        yuvImage.compressToJpeg(rect, 100, outputStream)
        val jpegArray = outputStream.toByteArray()

        // Decode the rotated JPEG image
        val rotatedBitmap = BitmapFactory.decodeByteArray(jpegArray, 0, jpegArray.size)

        // Apply the rotation matrix to the bitmap
        return Bitmap.createBitmap(
            rotatedBitmap,
            0,
            0,
            rotatedBitmap.width,
            rotatedBitmap.height,
            rotationMatrix,
            true
        )
    }

    companion object {
        private const val TAG = "CameraFragment"
    }
}
