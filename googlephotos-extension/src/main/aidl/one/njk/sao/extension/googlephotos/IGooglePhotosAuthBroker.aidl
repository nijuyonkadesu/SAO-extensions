package one.njk.sao.extension.googlephotos;

interface IGooglePhotosAuthBroker {
    boolean claim(String nonce);
    void complete(String nonce, String account, String loginUrl, String failure);
}
