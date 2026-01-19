package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
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

        // helpers
        fun Chip.setTri(state: MainViewModel.TriState) = when (state) {
            MainViewModel.TriState.DEFAULT -> apply { isChecked = false; chipIcon = null }
            MainViewModel.TriState.INCLUDE -> apply { isChecked = true; setCheckedIconResource(R.drawable.ic_check) }
            MainViewModel.TriState.EXCLUDE -> apply { isChecked = true; setCheckedIconResource(R.drawable.ic_close) }
        }

        fun Chip.setBin(flag: Boolean) = apply { isChecked = flag; chipIcon = null }

        fun nextTri(s: MainViewModel.TriState) = when (s) {
            MainViewModel.TriState.DEFAULT -> MainViewModel.TriState.INCLUDE
            MainViewModel.TriState.INCLUDE -> MainViewModel.TriState.EXCLUDE
            MainViewModel.TriState.EXCLUDE -> MainViewModel.TriState.DEFAULT
        }

        // Helper binders
        fun bindBinary(chip: Chip, getter: () -> Boolean, setter: (Boolean) -> Unit) {
            chip.setOnClickListener {
                setter(!getter())
                vm.memberFilterLive.postValue(vm.memberFilter)
            }
        }

        fun bindTriState(chip: Chip, getter: () -> MainViewModel.TriState, setter: (MainViewModel.TriState) -> Unit) {
            chip.setOnClickListener {
                setter(nextTri(getter()))
                vm.memberFilterLive.postValue(vm.memberFilter)
            }
        }

        // keep UI in sync with ViewModel
        vm.memberFilterLive.observe(viewLifecycleOwner) { f ->
            binding.apply {
                visibilityPublic.setBin(f.visibilityPublic)
                visibilityProtected.setBin(f.visibilityProtected)
                visibilityPrivate.setBin(f.visibilityPrivate)
                visibilityPackage.setBin(f.visibilityPackage)

                kindMethods.setBin(f.kindMethods)
                kindFields.setBin(f.kindFields)

                modStatic.setTri(f.isStatic)
                modFinal.setTri(f.isFinal)
            }
        }

        // wire interactions concisely using the bind helpers
        binding.apply {
            bindBinary(visibilityPublic, { vm.memberFilter.visibilityPublic }, { vm.memberFilter.visibilityPublic = it })
            bindBinary(visibilityProtected, { vm.memberFilter.visibilityProtected }, { vm.memberFilter.visibilityProtected = it })
            bindBinary(visibilityPrivate, { vm.memberFilter.visibilityPrivate }, { vm.memberFilter.visibilityPrivate = it })
            bindBinary(visibilityPackage, { vm.memberFilter.visibilityPackage }, { vm.memberFilter.visibilityPackage = it })

            bindBinary(kindMethods, { vm.memberFilter.kindMethods }, { vm.memberFilter.kindMethods = it })
            bindBinary(kindFields, { vm.memberFilter.kindFields }, { vm.memberFilter.kindFields = it })

            bindTriState(modStatic, { vm.memberFilter.isStatic }, { vm.memberFilter.isStatic = it })
            bindTriState(modFinal, { vm.memberFilter.isFinal }, { vm.memberFilter.isFinal = it })
        }
    }
}
