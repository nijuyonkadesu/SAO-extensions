package one.njk.sao.extension.googlephotos;

/** One nonce-scoped authentication operation; cancellation never starts another request. */
oneway interface IGooglePhotosAuthCancellation {
    void cancel(String nonce);
}
