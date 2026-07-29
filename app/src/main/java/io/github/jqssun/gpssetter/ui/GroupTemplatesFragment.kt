package io.github.jqssun.gpssetter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.GroupTemplateAdapter
import io.github.jqssun.gpssetter.databinding.FragmentGroupTemplatesBinding
import io.github.jqssun.gpssetter.ui.viewmodel.TemplateViewModel
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GroupTemplatesFragment : Fragment() {

    private var _binding: FragmentGroupTemplatesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TemplateViewModel by activityViewModels()
    private lateinit var adapter: GroupTemplateAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGroupTemplatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = GroupTemplateAdapter(
            onClick = { group ->
                (activity as? TemplateActivity)?.showGroupTemplateDialog(group)
            },
            onLongClick = { group ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(group.name)
                    .setMessage(getString(R.string.delete_favorite_item))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.deleteGroupTemplate(group.id)
                        requireContext().showToast(getString(R.string.template_deleted))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        )
        binding.rvGroupTemplates.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGroupTemplates.adapter = adapter
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groupTemplates.collect { groups ->
                    adapter.submitList(groups)
                    binding.emptyStateGroup.visibility =
                        if (groups.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvGroupTemplates.visibility =
                        if (groups.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
