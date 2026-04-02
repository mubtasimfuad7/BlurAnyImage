package com.example.myapplication

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.FragmentFirstBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var selectedImageUri: Uri? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            binding.imageView.setImageURI(uri)
            binding.imageView.setPadding(0, 0, 0, 0)
            binding.imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            binding.imageView.setRenderEffect(null)
            binding.sliderBlur.value = 0f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore state if available
        savedInstanceState?.let { bundle ->
            val uriString = bundle.getString("selectedImageUri")
            if (uriString != null) {
                selectedImageUri = Uri.parse(uriString)
                binding.imageView.setImageURI(selectedImageUri)
                binding.imageView.setPadding(0, 0, 0, 0)
                binding.imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                
                val radius = bundle.getFloat("blurRadius", 0f)
                binding.sliderBlur.value = radius
                applyBlur(radius)
            }
        }

        binding.buttonSelect.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.buttonSave.setOnClickListener {
            saveBlurredImage()
        }

        binding.sliderBlur.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                applyBlur(value)
            }
        }

        // Feature 1: "Touch to Compare" - Long press to see original
        binding.imageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.imageView.setRenderEffect(null)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    applyBlur(binding.sliderBlur.value)
                    true
                }
                else -> false
            }
        }
    }

    private fun applyBlur(radius: Float) {
        if (radius > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            binding.imageView.setRenderEffect(blurEffect)
        } else {
            binding.imageView.setRenderEffect(null)
        }
    }

    private fun saveBlurredImage() {
        val uri = selectedImageUri ?: run {
            Toast.makeText(requireContext(), "Please select an image first", Toast.LENGTH_SHORT).show()
            return
        }

        val radius = binding.sliderBlur.value
        
        // Feature 2: Loading State and Background Processing
        binding.saveProgress.visibility = View.VISIBLE
        binding.buttonSave.isEnabled = false
        binding.buttonSelect.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val source = ImageDecoder.createSource(requireContext().contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }

                    val blurredBitmap = if (radius > 0) {
                        blurBitmap(bitmap, radius)
                    } else {
                        bitmap
                    }

                    performSave(blurredBitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            binding.saveProgress.visibility = View.GONE
            binding.buttonSave.isEnabled = true
            binding.buttonSelect.isEnabled = true

            if (result != null) {
                Toast.makeText(requireContext(), "Image saved to gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun blurBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        // Scaling down the bitmap for a much stronger blur effect with RenderScript's 25f limit
        val scale = 0.4f
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val inputBitmap = Bitmap.createScaledBitmap(bitmap, width, height, false)
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val rs = RenderScript.create(requireContext())
        val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        val allIn = Allocation.createFromBitmap(rs, inputBitmap)
        val allOut = Allocation.createFromBitmap(rs, outBitmap)
        
        // RenderScript limit is 25f. By scaling down the image 2.5x, 
        // a 25f radius becomes equivalent to ~62f on the original size.
        // We map our 0-100 slider to this range.
        val effectiveRadius = (radius / 4f).coerceIn(0.1f, 25f)
        
        blurScript.setRadius(effectiveRadius)
        blurScript.setInput(allIn)
        blurScript.forEach(allOut)
        allOut.copyTo(outBitmap)
        
        rs.destroy()
        
        // Scale back up to original size
        return Bitmap.createScaledBitmap(outBitmap, bitmap.width, bitmap.height, true)
    }

    private suspend fun performSave(bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val filename = "Blurred_${System.currentTimeMillis()}.jpg"
        val contentResolver = requireContext().contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BlurredImages")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        try {
            val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            imageUri?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, contentValues, null, null)
                uri
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedImageUri?.let { outState.putString("selectedImageUri", it.toString()) }
        outState.putFloat("blurRadius", binding.sliderBlur.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}