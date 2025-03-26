// HomeFragment.kt
package com.example.yololitertobjectdetection.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.yololitertobjectdetection.ModelPreferences
import com.example.yololitertobjectdetection.R
import com.example.yololitertobjectdetection.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set click listener for the button
        binding.btnStartDetection.setOnClickListener {
            showModelSelectionDialog()
        }
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf(
            "Small - Balanced Performance",
            "Medium - Faster & Accurate",
            "Large - More Accurate & Slower"
        )

        // Find the current model
        val currentModel = ModelPreferences.getSelectedModel(requireContext())
        val initialSelection = when(currentModel) {
            "yolov10n_float16.tflite" -> 0
            "yolo11n_float16.tflite" -> 1
            "yolov12n_float16.tflite" -> 2
            else -> 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Select Detection Model")
            .setSingleChoiceItems(models, initialSelection) { dialog, which ->
                // Save the selected model
                val selectedModel = when(which) {
                    0 -> "yolov10n_float16.tflite"
                    1 -> "yolo11n_float16.tflite"
                    2 -> "yolov12n_float16.tflite"
                    else -> "yolov10n_float16.tflite"
                }
                ModelPreferences.saveSelectedModel(requireContext(), selectedModel)

                // Dismiss dialog and navigate
                dialog.dismiss()
                findNavController().navigate(R.id.action_homeFragment_to_cameraFragment)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}