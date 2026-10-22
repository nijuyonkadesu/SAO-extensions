package one.njk.sao.extension.googlephotos;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** API 102. Lifecycle interceptors do no account/network work without a settings-initiated one-use nonce. */
public final class GooglePhotosVectorModule extends XposedModule {
    private static final String PHOTOS = "com.google.android.apps.photos";
    private static final String EXTRA = "one.njk.sao.googlephotos.CONNECT";
    private Handler main;
    private String modulePackage;
    private int moduleUid;
    private boolean installed;
    private String lastNonce;
    private String pendingNonce;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer() || !PHOTOS.equals(param.getProcessName())) return;
        main = new Handler(Looper.getMainLooper());
        modulePackage = getModuleApplicationInfo().packageName;
        moduleUid = getModuleApplicationInfo().uid;
        Log.i("SAO.GooglePhotos", "Vector module loaded in Google Photos");
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (main == null || installed || !PHOTOS.equals(param.getPackageName())) return;
        try {
            Method create = Instrumentation.class.getDeclaredMethod("callActivityOnCreate", Activity.class, Bundle.class);
            hook(create).intercept(chain -> {
                // Capture before Photos can replace its launch Intent or forward to another Activity.
                remember(((Activity) chain.getArg(0)).getIntent());
                return chain.proceed();
            });
            Method resume = Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class);
            hook(resume).intercept(chain -> {
                Object result = chain.proceed();
                Activity activity = (Activity) chain.getArg(0);
                connect(activity, activity.getIntent());
                return result;
            });
            Method incoming = Instrumentation.class.getDeclaredMethod("callActivityOnNewIntent", Activity.class, Intent.class);
            hook(incoming).intercept(chain -> {
                Object result = chain.proceed();
                connect((Activity) chain.getArg(0), (Intent) chain.getArg(1));
                return result;
            });
            installed = true;
            Log.i("SAO.GooglePhotos", "Lifecycle hooks installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w("SAO.GooglePhotos", "Lifecycle hook setup failed: " + error.getClass().getSimpleName());
        }
    }

    private void remember(Intent intent) {
        if (intent == null) return;
        final String nonce;
        try { nonce = intent.getStringExtra(EXTRA); }
        catch (RuntimeException ignored) { return; }
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}") || nonce.equals(lastNonce)) return;
        intent.removeExtra(EXTRA);
        pendingNonce = nonce;
        Log.i("SAO.GooglePhotos", "Connect intent received");
    }
    private void connect(Activity activity, Intent intent) {
        if (!PHOTOS.equals(activity.getPackageName()) || activity.isFinishing() || activity.isDestroyed()) return;
        remember(intent);
        String nonce = pendingNonce;
        if (nonce == null || nonce.equals(lastNonce)) return;
        pendingNonce = null;
        lastNonce = nonce;
        main.post(() -> new Connection(activity, nonce).start());
    }

    private final class Connection implements ServiceConnection {
        final WeakReference<Activity> activity;
        final Context context;
        final String nonce;
        final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "SAO Photos connect"); thread.setDaemon(true); return thread;
        });
        IGooglePhotosAuthBroker broker;
        AccountManagerFuture<Bundle> auth;
        AlertDialog dialog;
        boolean bound;
        boolean finished;
        final Runnable timeout = () -> finish("", "", "CONNECT_CANCELLED");

        Connection(Activity current, String nonce) {
            activity = new WeakReference<>(current); context = current.getApplicationContext(); this.nonce = nonce;
        }
        void start() {
            try {
                if (context.getPackageManager().getApplicationInfo(modulePackage, 0).uid != moduleUid) {
                    Log.w("SAO.GooglePhotos", "Extension UID check failed"); close(); return;
                }
                bound = context.bindService(new Intent().setComponent(new ComponentName(modulePackage,
                    modulePackage + ".GooglePhotosAuthService")), this, Context.BIND_AUTO_CREATE);
                if (!bound) { Log.w("SAO.GooglePhotos", "Auth service bind rejected"); close(); }
                else { Log.i("SAO.GooglePhotos", "Auth service bind requested"); main.postDelayed(timeout, 110_000); }
            } catch (Exception error) {
                Log.w("SAO.GooglePhotos", "Auth service bind failed: " + error.getClass().getSimpleName()); close();
            }
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (finished) return;
            Log.i("SAO.GooglePhotos", "Auth service connected");
            broker = IGooglePhotosAuthBroker.Stub.asInterface(binder);
            io.execute(() -> {
                boolean allowed;
                try { allowed = broker.claim(nonce); } catch (Exception ignored) { allowed = false; }
                boolean accepted = allowed;
                Log.i("SAO.GooglePhotos", accepted ? "Auth request accepted" : "Auth request rejected");
                main.post(() -> { if (accepted && !finished) choose(); else close(); });
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { close(); }
        @Override public void onNullBinding(ComponentName name) { close(); }
        void choose() {
            io.execute(() -> {
                try {
                    Account[] accounts = AccountManager.get(context).getAccountsByType("com.google");
                    main.post(() -> showAccounts(accounts));
                } catch (Exception ignored) { main.post(() -> finish("", "", "AUTH_UNSUPPORTED")); }
            });
        }
        void showAccounts(Account[] accounts) {
            if (finished) return;
            Activity current = activity.get();
            if (current == null || current.isFinishing() || current.isDestroyed()) { finish("", "", "CONNECT_CANCELLED"); return; }
            try {
                if (accounts.length == 0) { finish("", "", "ACCOUNT_MISSING"); return; }
                String[] names = new String[accounts.length];
                for (int i = 0; i < accounts.length; i++) names[i] = accounts[i].name;
                dialog = new AlertDialog.Builder(current).setTitle("Connect Google Photos to SAO")
                    .setItems(names, (d, index) -> token(current, accounts[index]))
                    .setNegativeButton(android.R.string.cancel, (d, which) -> finish("", "", "CONNECT_CANCELLED"))
                    .setOnCancelListener(d -> finish("", "", "CONNECT_CANCELLED")).show();
                Log.i("SAO.GooglePhotos", "Account chooser displayed");
            } catch (Exception ignored) { finish("", "", "AUTH_UNSUPPORTED"); }
        }
        void token(Activity current, Account account) {
            try {
                // A short-lived web-login URL, never a Google master/refresh token or account database export.
                // Photos' own WebAuth flow uses this AccountManager token type.
                String scope = "weblogin:service=gaia&continue=" + android.net.Uri.encode("https://photos.google.com/");
                Log.i("SAO.GooglePhotos", "Native web login requested");
                auth = AccountManager.get(context).getAuthToken(account, scope, new Bundle(), current, future -> {
                    try {
                        String url = future.getResult().getString(AccountManager.KEY_AUTHTOKEN);
                        if (url == null || !url.startsWith("https://accounts.google.com/MergeSession?") || url.length() > 16384)
                            finish("", "", "AUTH_UNSUPPORTED");
                        else {
                            // Clear only the local one-use token cache, as Photos' own WebAuth flow does.
                            io.execute(() -> {
                                try { AccountManager.get(context).invalidateAuthToken("com.google", url); }
                                catch (RuntimeException ignored) { }
                            });
                            finish(account.name, url, "");
                        }
                    } catch (android.accounts.OperationCanceledException ignored) { finish("", "", "CONNECT_CANCELLED"); }
                    catch (Exception ignored) { finish("", "", "HTTP_401"); }
                }, main);
            } catch (Exception ignored) { finish("", "", "AUTH_UNSUPPORTED"); }
        }
        void finish(String account, String url, String failure) {
            if (finished) return;
            Log.i("SAO.GooglePhotos", failure.isEmpty() ? "Native web login completed" : "Native connection failed: " + failure);
            finished = true;
            main.removeCallbacks(timeout);
            io.execute(() -> {
                boolean delivered = false;
                try { if (broker != null) { broker.complete(nonce, account, url, failure); delivered = true; } }
                catch (Exception ignored) { }
                boolean returnToSettings = delivered;
                main.post(() -> {
                    Activity current = activity.get();
                    if (returnToSettings && current != null && !current.isFinishing() && current.hasWindowFocus())
                        current.moveTaskToBack(true);
                    close();
                });
            });
        }
        void close() {
            finished = true; main.removeCallbacks(timeout);
            if (dialog != null) { dialog.setOnCancelListener(null); dialog.dismiss(); dialog = null; }
            if (auth != null && !auth.isDone()) auth.cancel(true);
            if (bound) { try { context.unbindService(this); } catch (Exception ignored) { } bound = false; }
            io.shutdown(); activity.clear();
        }
    }
}
