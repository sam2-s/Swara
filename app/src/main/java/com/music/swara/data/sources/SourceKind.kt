package com.music.swara.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose. **[rank] is the order sources are tried** — see
 * `SourceRegistry.active()`, which sorts on it — so the sources a user added
 * come first, then the legacy built-in module kind, then JioSaavn, then
 * YouTube Music. Adding a source means adding a [MusicSource]
 * implementation and an entry here, which is the point — every protocol the
 * app speaks is one someone can read in this repo, and a source can't teach
 * the app a new way to behave after it ships.
 *
 * What varies per *instance* — which index, whose module — is [SourceConfig].
 */
enum class SourceKind(
    val label: String,
    val detail: String,
    /** The chips under the name on the sources screen. */
    val labels: List<String>,
    /** Whether an instance needs a URL before it can do anything — and so needs an editor. */
    val needsServer: Boolean,
    /** Whether this kind can serve bit-exact audio when asked. */
    val canServeLossless: Boolean,
    /**
     * Whether asking this kind for a stream can ever produce audio.
     *
     * False for catalogue-only kinds, which exist so the search UI can show
     * rows from them — their [MusicSource.stream] is null by construction.
     * Every stream-finding walk goes through
     * [SourceResolver.bestAcross][com.music.swara.data.sources.SourceResolver],
     * which skips these before a single search is spent: asking a catalogue
     * whether it can *play* something is a network call whose answer is known
     * in advance. Search and import paths call `search` directly and are
     * unaffected.
     */
    val servesStreams: Boolean = true,
    /**
     * Whether this kind answers quickly enough to be worth asking *before* a
     * track is played, so its copy can be pinned and cached ahead of time.
     *
     * Measured on this device, the gap is not close: JioSaavn answers a search
     * and hands back a stream URL in about 0.4s, while a module index takes
     * 7-13s to walk its backends — and read-ahead runs on the track *after* the
     * one playing, so a lookup that slow is usually still going when the
     * listener arrives. A wasted JioSaavn resolve costs one HTTP round trip; a
     * wasted module resolve costs a QuickJS engine, an index fetch and several
     * backend searches. The first is worth spending speculatively and the
     * second is not.
     *
     * False for [YOUTUBE] as well, though it *is* warmed ahead of time — that
     * happens through its own read-ahead in
     * [AudioCache][com.music.swara.playback.AudioCache], which speaks video
     * ids directly and needs no cross-source match to find the track.
     */
    val worthPrefetching: Boolean = false,
    /**
     * Where this kind sits in the walk, low first.
     *
     * Separate from [ordinal] because two kinds share a position: an addon and
     * a module index are both sources the user added on purpose, and which of
     * *those* is asked first is theirs to set by dragging — see
     * [SourceRegistry.reorderAddons][com.music.swara.data.sources.SourceRegistry.reorderAddons].
     * Sorting on `ordinal` made that impossible to express: a stable sort would
     * always put every addon ahead of every module index whatever order the
     * list was in, so the drag would have moved a row on screen and changed
     * nothing about what was actually asked first.
     */
    val rank: Int,
) {
    /**
     * An addon server the user pointed at themselves.
     *
     * The one kind that can be added on the sources screen, and the answer to
     * "who supplies the catalogue" now that it is nobody this app ships with.
     * An addon is a plain HTTP server — `/manifest.json`, `/search`, `/stream`,
     * JSON both ways — so unlike [MODULE] there is no code to download and
     * nothing to sandbox: the app asks questions and reads answers.
     *
     * Any number may be configured, tried in the order they were added, which
     * is why this carries no single-instance rule of the sort [CUSTOM_MODULE]
     * had. Declared first so a source the user chose on purpose is asked before
     * anything the app fell back to.
     */
    ADDON(
        label = "Addon",
        detail = "An addon server you host or were given a link to. Searched and streamed over " +
            "plain HTTP — no code is downloaded or run.",
        labels = listOf("FLAC", "Lossless", "Hi-Res"),
        needsServer = true,
        canServeLossless = true,
        // Literal, not [USER_ADDED]: an enum entry is constructed before
        // its own companion exists.
        rank = 0,
    ),

    /**
     * A module index the user pointed at themselves.
     *
     * Legacy, and no longer offered: the sources screen has no way to add one
     * and [ADDON] is what that entry point creates now. Kept as a kind so an
     * index configured by an earlier build keeps working for whoever still has
     * one — same protocol as [MODULE], served by the same [ModuleSource].
     */
    CUSTOM_MODULE(
        label = "Custom module",
        detail = "Your own compatible module index. Tried before the built-in one.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
        // Literal, not [USER_ADDED]: an enum entry is constructed before
        // its own companion exists.
        rank = 0,
    ),

    /**
     * A URL to a compatible module-index JSON.
     *
     * The index lists JS plugin descriptors; each plugin ships a JS file
     * that exports `searchTracks()` and `getTrackStreamUrl()`. The app
     * fetches the index, loads each plugin's JS into a QuickJS sandbox,
     * and calls those functions.
     *
     * The JS runs in a sandboxed QuickJS VM with no access to the Android
     * runtime, only to a wired-in `fetch()` implementation.
     *
     * Legacy, and no longer created by anything: this kind was only ever
     * seeded from a build-time index URL, which this build no longer carries.
     * [SourceRegistry.init] drops any left over from an install that did. Kept
     * so the enum a stored config names still exists — see the decode there,
     * which loses an entry whose kind it cannot resolve.
     */
    MODULE(
        label = "Module source",
        detail = "A URL to a compatible module index. Modules are JS plugins that " +
            "can search and stream from services like Tidal, Qobuz, Apple Music and more.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
        rank = 1,
    ),

    /**
     * Qobuz, as a first-class source rather than something a module reaches.
     *
     * Serves progressive FLAC directly — no manifest, no segments — which is
     * what makes it the one lossless source whose downloads need no assembly
     * step: the URL [stream] hands back is the file. `format_id` 5/6/7 select
     * 16/44.1, 24/≤96 and 24/>96, and the sample rate and bit depth travel with
     * the stream rather than being assumed.
     *
     * Needs a user token plus an `app_id`/`app_secret` pair, so it is the one
     * new kind that is incomplete until something is typed in — see
     * [SourceConfig.isComplete].
     */
    QOBUZ(
        label = "Qobuz",
        detail = "FLAC up to 24-bit/192kHz from your own Qobuz account. Needs a user token " +
            "and an app_id/app_secret pair.",
        labels = listOf("FLAC", "Lossless", "Hi-Res"),
        needsServer = false,
        canServeLossless = true,
        worthPrefetching = true,
        rank = 2,
    ),

    /**
     * Tidal, as a first-class source.
     *
     * Serves FLAC as MPEG-DASH segments rather than a progressive file, so a
     * Tidal stream is a manifest plus a list of ranges to fetch and concatenate
     * — see [TidalSource]. That is more machinery than Qobuz needs and is the
     * whole reason this kind sits below it: when both are configured, the
     * source that can answer with one GET is asked first.
     *
     * The hi-res rung is guarded rather than trusted. Tidal answers a
     * `HI_RES_LOSSLESS` request for a CD-only master with HTTP 200 and 16-bit
     * audio instead of an error, so a resolver that stops at the first success
     * never reaches the 24-bit master later in the candidate list.
     */
    TIDAL(
        label = "Tidal",
        detail = "FLAC up to 24-bit/192kHz from your own Tidal account, delivered as DASH " +
            "segments. Needs an access token.",
        labels = listOf("FLAC", "Lossless", "Hi-Res"),
        needsServer = false,
        canServeLossless = true,
        worthPrefetching = true,
        rank = 3,
    ),

    /**
     * Apple Music, as a *catalogue* only.
     *
     * Deliberately not a streaming source. Apple serves ALAC where it serves
     * lossless at all, and its catalogue API answers without credentials, so
     * the useful thing to do with it is search and import — a row found here
     * is played through YouTube Music, which is the only source with a
     * streamable rendition of the same recording. [stream] therefore returns
     * null and the kind sorts below everything that can actually serve audio,
     * so it is never asked for a URL it cannot produce.
     *
     * That is a real limitation and not a cosmetic one: an Apple Music row
     * plays at YouTube's Opus ceiling, not at Apple's. It is the trade for
     * needing no Apple credentials at all.
     */
    APPLE(
        label = "Apple Music",
        detail = "Browse, search and import playlists from the public Apple Music catalogue. " +
            "Playback goes through YouTube Music.",
        labels = listOf("Catalogue", "Import"),
        needsServer = false,
        canServeLossless = false,
        servesStreams = false,
        rank = 4,
    ),

    JIOSAAVN(
        label = "JioSaavn",
        detail = "Optional JioSaavn streams up to 320kbps AAC/MP4. Catalogue matching can select the wrong song.",
        labels = listOf("High Quality", "320kbps"),
        needsServer = false,
        canServeLossless = false,
        worthPrefetching = true,
        rank = 5,
    ),

    /**
     * The source the app was built on, listed here so it always has a fixed
     * place: last, behind every other kind. It cannot be removed — see
     * [SourceRegistry]. Nothing else in the app can supply a home feed, a
     * radio station or a related-tracks queue.
     */
    YOUTUBE(
        label = "YouTube Music",
        detail = "The full catalogue, at Opus up to about 171 kbps. Lossy — there is no " +
            "lossless rendition to ask for.",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        canServeLossless = false,
        rank = 6,
    ),
    ;

    /** Whether this is a source the user configured, rather than one that ships. */
    val isUserAdded: Boolean get() = rank == USER_ADDED

    companion object {
        /**
         * The shared rank of every source the user added. Their order among
         * themselves is the stored list order, which is what a drag rewrites.
         */
        const val USER_ADDED = 0
    }
}
