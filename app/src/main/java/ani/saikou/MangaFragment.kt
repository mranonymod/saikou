package ani.saikou

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.view.marginBottom
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.saikou.anilist.AnilistMangaViewModel
import ani.saikou.anilist.SearchResults
import ani.saikou.anilist.getUserId
import ani.saikou.databinding.FragmentMangaBinding
import ani.saikou.media.MediaAdaptor
import ani.saikou.media.ProgressAdapter
import ani.saikou.settings.UserInterfaceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MangaFragment : Fragment() {
    private var _binding: FragmentMangaBinding? = null
    private val binding get() = _binding!!

    private var uiSettings: UserInterfaceSettings = loadData("ui_settings") ?: UserInterfaceSettings()

    val model: AnilistMangaViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMangaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView();_binding = null
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val scope = viewLifecycleOwner.lifecycleScope

        var height = statusBarHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = activity?.window?.decorView?.rootWindowInsets?.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    height = max(
                        statusBarHeight,
                        min(
                            displayCutout.boundingRects[0].width(),
                            displayCutout.boundingRects[0].height()
                        )
                    )
                }
            }
        }
        binding.mangaRefresh.setSlingshotDistance(height + 128)
        binding.mangaRefresh.setProgressViewEndTarget(false, height + 128)
        binding.mangaRefresh.setOnRefreshListener {
            Refresh.activity[this.hashCode()]!!.postValue(true)
        }

        binding.mangaPageRecyclerView.updatePaddingRelative(bottom = navBarHeight + 160f.px)

        val mangaPageAdapter = MangaPageAdapter()
        var loading = true
        if (model.notSet) {
            model.notSet = false
            model.searchResults = SearchResults(
                "MANGA",
                isAdult = false,
                onList = false,
                results = arrayListOf(),
                hasNextPage = true,
                sort = "Popular"
            )
        }
        val popularAdaptor = MediaAdaptor(1, model.searchResults.results, requireActivity())
        val progressAdaptor = ProgressAdapter(searched = model.searched)
        binding.mangaPageRecyclerView.adapter = ConcatAdapter(mangaPageAdapter, popularAdaptor, progressAdaptor)
        val layout = LinearLayoutManager(requireContext())
        binding.mangaPageRecyclerView.layoutManager = layout

        var visible = false
        fun animate() {
            val start = if (visible) 0f else 1f
            val end = if (!visible) 0f else 1f
            ObjectAnimator.ofFloat(binding.mangaPageScrollTop, "scaleX", start, end).apply {
                duration = 300
                interpolator = OvershootInterpolator(2f)
                start()
            }
            ObjectAnimator.ofFloat(binding.mangaPageScrollTop, "scaleY", start, end).apply {
                duration = 300
                interpolator = OvershootInterpolator(2f)
                start()
            }
        }

        binding.mangaPageScrollTop.setOnClickListener {
            binding.mangaPageRecyclerView.scrollToPosition(4)
            binding.mangaPageRecyclerView.smoothScrollToPosition(0)
        }

        binding.mangaPageRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                if (!v.canScrollVertically(1)) {
                    if (model.searchResults.hasNextPage && model.searchResults.results.isNotEmpty() && !loading) {
                        scope.launch(Dispatchers.IO) {
                            loading = true
                            model.loadNextPage(model.searchResults)
                        }
                    }
                }
                if (layout.findFirstVisibleItemPosition() > 1 && !visible) {
                    binding.mangaPageScrollTop.visibility = View.VISIBLE
                    visible = true
                    animate()
                }

                if (!v.canScrollVertically(-1)) {
                    visible = false
                    animate()
                    scope.launch {
                        delay(300)
                        binding.mangaPageScrollTop.visibility = View.GONE
                    }
                }

                super.onScrolled(v, dx, dy)
            }
        })
        mangaPageAdapter.ready.observe(viewLifecycleOwner) { i ->
            if (i == true) {
                model.getTrendingNovel().observe(viewLifecycleOwner) {
                    if (it != null) {
                        mangaPageAdapter.updateNovel(MediaAdaptor(0, it, requireActivity()))
                    }
                }
                if (mangaPageAdapter.trendingViewPager != null) {
                    mangaPageAdapter.updateHeight()
                    model.getTrending().observe(viewLifecycleOwner) {
                        if (it != null) {
                            mangaPageAdapter.updateTrending(
                                MediaAdaptor(
                                    if (uiSettings.smallView) 3 else 2,
                                    it,
                                    requireActivity(),
                                    viewPager = mangaPageAdapter.trendingViewPager
                                )
                            )
                            mangaPageAdapter.updateAvatar()
                        }
                    }
                }
                binding.mangaPageScrollTop.translationY = -(navBarHeight + bottomBar.height + bottomBar.marginBottom).toFloat()

            }
        }

        var oldIncludeList = true

        mangaPageAdapter.onIncludeListClick = { checked ->
            oldIncludeList = !checked
            loading = true
            model.searchResults.results.clear()
            popularAdaptor.notifyDataSetChanged()
            scope.launch(Dispatchers.IO) {
                model.loadPopular("MANGA", sort = "Popular", onList = checked)
            }
        }

        model.getPopular().observe(viewLifecycleOwner) {
            if (it != null) {
                println("old : $oldIncludeList & it ${it.onList ?: true}")
                if (oldIncludeList == (it.onList != false)) {
                    val prev = model.searchResults.results.size
                    model.searchResults.results.addAll(it.results)
                    popularAdaptor.notifyItemRangeInserted(prev, it.results.size)
                } else {
                    model.searchResults.results.addAll(it.results)
                    popularAdaptor.notifyDataSetChanged()
                    oldIncludeList = it.onList ?: true
                }
                model.searchResults.onList = it.onList
                model.searchResults.hasNextPage = it.hasNextPage
                model.searchResults.page = it.page
                if (it.hasNextPage)
                    progressAdaptor.bar?.visibility = View.VISIBLE
                else {
                    toastString("DAMN! YOU TRULY ARE JOBLESS\nYOU REACHED THE END")
                    progressAdaptor.bar?.visibility = View.GONE
                }
                loading = false
            }
        }

        fun load() = scope.launch(Dispatchers.Main) {
            mangaPageAdapter.updateAvatar()
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(false) }
        live.observe(viewLifecycleOwner) {
            if (it) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        getUserId{
                            load()
                        }
                        model.loaded = true
                        model.loadTrending()
                        model.loadTrendingNovel()
                        model.loadPopular("MANGA", sort = "Popular")
                    }
                    live.postValue(false)
                    _binding?.mangaRefresh?.isRefreshing = false
                }
            }
        }
    }

    override fun onResume() {
        if (!model.loaded) Refresh.activity[this.hashCode()]!!.postValue(true)
        super.onResume()
    }

}