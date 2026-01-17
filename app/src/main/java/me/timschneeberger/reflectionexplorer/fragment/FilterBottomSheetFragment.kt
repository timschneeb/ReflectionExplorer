package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.databinding.BottomSheetFiltersBinding
import me.timschneeberger.reflectionexplorer.model.MainViewModel

class FilterBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var binding: BottomSheetFiltersBinding
    private lateinit var vm: MainViewModel

    companion object {
        fun newInstance(): FilterBottomSheetFragment = FilterBottomSheetFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = BottomSheetFiltersBinding.inflate(inflater, container, false)
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // helper to update chip visuals for tri-state
        fun applyState(chip: Chip, state: MainViewModel.TriState) {
            when (state) {
                MainViewModel.TriState.DEFAULT -> {
                    chip.isChecked = false
                    chip.chipIcon = null
                }
                MainViewModel.TriState.INCLUDE -> {
                    chip.isChecked = true
                    chip.setCheckedIconResource(R.drawable.ic_check)
                }
                MainViewModel.TriState.EXCLUDE -> {
                    chip.isChecked = true
                    chip.setCheckedIconResource(R.drawable.ic_close)
                }
            }
        }

        // initialize with vm state
        val f = vm.memberFilter
        applyState(binding.visibilityPublic, f.visibilityPublic)
        applyState(binding.visibilityProtected, f.visibilityProtected)
        applyState(binding.visibilityPrivate, f.visibilityPrivate)

        applyState(binding.kindMethods, f.kindMethods)
        applyState(binding.kindFields, f.kindFields)

        applyState(binding.modStatic, f.isStatic)
        applyState(binding.modFinal, f.isFinal)

        // cycle helper
        fun next(s: MainViewModel.TriState) = when (s) {
            MainViewModel.TriState.DEFAULT -> MainViewModel.TriState.INCLUDE
            MainViewModel.TriState.INCLUDE -> MainViewModel.TriState.EXCLUDE
            MainViewModel.TriState.EXCLUDE -> MainViewModel.TriState.DEFAULT
        }

        // attach cycling on chips (examples per property)
        binding.visibilityPublic.setOnClickListener {
            f.visibilityPublic = next(f.visibilityPublic)
            applyState(binding.visibilityPublic, f.visibilityPublic)
        }
        binding.visibilityProtected.setOnClickListener {
            f.visibilityProtected = next(f.visibilityProtected)
            applyState(binding.visibilityProtected, f.visibilityProtected)
        }
        binding.visibilityPrivate.setOnClickListener {
            f.visibilityPrivate = next(f.visibilityPrivate)
            applyState(binding.visibilityPrivate, f.visibilityPrivate)
        }

        binding.kindMethods.setOnClickListener {
            f.kindMethods = next(f.kindMethods)
            applyState(binding.kindMethods, f.kindMethods)
        }
        binding.kindFields.setOnClickListener {
            f.kindFields = next(f.kindFields)
            applyState(binding.kindFields, f.kindFields)
        }

        binding.modStatic.setOnClickListener {
            f.isStatic = next(f.isStatic)
            applyState(binding.modStatic, f.isStatic)
        }
        binding.modFinal.setOnClickListener {
            f.isFinal = next(f.isFinal)
            applyState(binding.modFinal, f.isFinal)
        }

        binding.filtersReset.setOnClickListener {
            vm.memberFilter.apply {
                visibilityPublic = MainViewModel.TriState.DEFAULT
                visibilityProtected = MainViewModel.TriState.DEFAULT
                visibilityPrivate = MainViewModel.TriState.DEFAULT
                isStatic = MainViewModel.TriState.DEFAULT
                isFinal = MainViewModel.TriState.DEFAULT
                kindMethods = MainViewModel.TriState.DEFAULT
                kindFields = MainViewModel.TriState.DEFAULT
            }
            // publish changes so observers update immediately
            vm.memberFilterLive.postValue(vm.memberFilter)
            dismiss()
        }

        binding.filtersApply.setOnClickListener {
            // publish changes so observers update immediately
            vm.memberFilterLive.postValue(vm.memberFilter)
            dismiss()
        }
    }
}
