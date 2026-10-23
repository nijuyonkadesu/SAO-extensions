package one.njk.sao.extension.googlephotos;
import one.njk.sao.extension.googlephotos.IGooglePhotosAuthCancellation;

interface IGooglePhotosAuthBroker {
    boolean claim(String nonce, IGooglePhotosAuthCancellation cancellation);
    boolean complete(String nonce, String account, String loginUrl, String failure);
}
