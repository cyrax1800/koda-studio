// tiketandroid: the large project (~900 modules, 5,000 source files, 15,000 with Java), for worst-case timings.
// `budgetMs` values are what we aim for; a slower result is reported as SLOW but doesn't fail the run.
const others = 'features/feature_accountv4/src/main/java/com/tiket/android/accountv4/setting/view/OthersActivity.kt';
const homeContainer = 'features/feature_home_container/src/main/java/com/tiket/feature/homecontainer/HomeContainerActivity.kt';
const chatRoom = 'features/feature_inbox/src/main/java/com/tiket/inbox/chat/chatroom/ChatRoomActivity.kt';
const cloudImages = 'libraries/lib_tds/src/main/java/com/tix/core/v4/util/CloudImageSource.kt';

module.exports = {
  name: 'tiketandroid',
  minutes: { sync: 20, ready: 30 },
  clean: [
    { file: homeContainer, budgetMs: 20_000 },
    { file: others, budgetMs: 10_000 },
    { file: chatRoom, budgetMs: 10_000 },
    { file: cloudImages, budgetMs: 10_000 },
  ],
  completions: [
    { name: 'Inte in an activity', file: others, find: 'setStatusBarColor(RTDS.color.TDS_N0)', offset: 36, insert: '\n        ', type: 'Inte', expect: ['Intent'], budgetMs: 1000 },
    { name: 'Inte again (warm)', file: others, find: 'setStatusBarColor(RTDS.color.TDS_N0)', offset: 36, insert: '\n        ', type: 'Inte', expect: ['Intent'], budgetMs: 300 },
    { name: 'member of a library class', file: others, find: 'setStatusBarColor(RTDS.color.TDS_N0)', offset: 36, insert: '\n        val i = android.content.Intent(this, OthersActivity::class.java)\n        ', type: 'i.put', expect: ['putExtra'], budgetMs: 300 },
    { name: 'stdlib in a large activity', file: homeContainer, find: 'val mappedData = mutableMapOf', offset: 0, insert: 'val x = ', type: 'mutableMa', expect: ['mutableMapOf'], budgetMs: 1000 },
  ],
  definitions: [
    { name: 'project class', file: others, find: 'TiketViewModelActivity<', offset: 3, expectFile: 'TiketViewModelActivity.kt', budgetMs: 1000 },
    { name: 'library class (sources)', file: others, find: 'ContextCompat.', offset: 3, expectFile: 'ContextCompat', budgetMs: 2000 },
  ],
  hovers: [{ name: 'stdlib function', file: homeContainer, find: 'mutableMapOf<Int', offset: 3, expect: 'mutableMapOf', budgetMs: 1000 }],
  references: [
    { name: 'SchedulerProvider (used in ~580 files)', file: 'libraries/lib_scheduler/src/main/java/com/tiket/lib/scheduler/SchedulerProvider.kt', find: 'interface SchedulerProvider', offset: 'interface '.length, min: 500, budgetMs: 15_000 },
  ],
  // The lenses of the first `resolve` declarations, as on screen.
  codeLens: [
    { name: 'CloudImageSource.kt (4,889 declarations)', file: cloudImages, resolve: 40, budgetMs: 15_000 },
    { name: 'HomeContainerActivity.kt', file: homeContainer, resolve: 40, budgetMs: 15_000 },
  ],
  formatting: undefined,
};
