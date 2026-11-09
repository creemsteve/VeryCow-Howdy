use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jlong, jstring},
    JNIEnv,
};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicI64, Ordering},
        Arc, Mutex, OnceLock,
    },
};
use vch_core::mobile::MobileClient;
use zeroize::Zeroizing;
struct Entry {
    mobile: Mutex<MobileClient>,
    pressed: AtomicBool,
}
type Session = Arc<Entry>;
static SESSIONS: OnceLock<Mutex<BTreeMap<i64, Session>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn sessions() -> &'static Mutex<BTreeMap<i64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
#[no_mangle]
pub extern "system" fn Java_chat_vch_flip_NativeClient_create(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
    password: JByteArray,
    models: JString,
    native_dir: JString,
) -> jlong {
    let result = (|| -> anyhow::Result<i64> {
        let directory: String = env.get_string(&directory)?.into();
        let models: String = env.get_string(&models)?.into();
        let native_dir: String = env.get_string(&native_dir)?.into();
        let bytes = Zeroizing::new(env.convert_byte_array(password)?);
        let password = Zeroizing::new(String::from_utf8(bytes.to_vec())?);
        std::env::set_var(
            "ORT_DYLIB_PATH",
            PathBuf::from(native_dir).join(if cfg!(windows) {
                "onnxruntime.dll"
            } else {
                "libonnxruntime.so"
            }),
        );
        let mobile = MobileClient::open(directory.into(), password, models.into())?;
        let handle = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions()
            .lock()
            .map_err(|_| anyhow::anyhow!("Session unavailable"))?
            .insert(
                handle,
                Arc::new(Entry {
                    mobile: Mutex::new(mobile),
                    pressed: AtomicBool::new(false),
                }),
            );
        Ok(handle)
    })();
    match result {
        Ok(handle) => handle,
        Err(error) => {
            let _ = env.throw_new("java/io/IOException", format!("{error:#}"));
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_chat_vch_flip_NativeClient_exchange(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
    input: JString,
) -> jstring {
    let result = (|| -> anyhow::Result<serde_json::Value> {
        let path: String = env.get_string(&path)?.into();
        let input: Zeroizing<String> = Zeroizing::new(env.get_string(&input)?.into());
        anyhow::ensure!(input.len() <= 28_000_000, "Request too large");
        let session = sessions()
            .lock()
            .map_err(|_| anyhow::anyhow!("Session unavailable"))?
            .get(&handle)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("Vault locked"))?;
        if path == "/media/media" {
            anyhow::ensure!(
                session.pressed.load(Ordering::Acquire),
                "Push-to-talk released"
            );
        }
        let mut mobile = session
            .mobile
            .lock()
            .map_err(|_| anyhow::anyhow!("Session unavailable"))?;
        if path == "/media/media" {
            anyhow::ensure!(
                session.pressed.load(Ordering::Acquire),
                "Push-to-talk released"
            );
        }
        mobile.request(&path, serde_json::from_str(&input)?)
    })();
    let out = match result {
        Ok(value) => value,
        Err(error) => serde_json::json!({"error":format!("{error:#}")}),
    };
    env.new_string(out.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_chat_vch_flip_NativeClient_gate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    pressed: jni::sys::jboolean,
) {
    if let Some(session) = sessions().lock().ok().and_then(|m| m.get(&handle).cloned()) {
        session.pressed.store(pressed != 0, Ordering::Release);
    }
}
#[no_mangle]
pub extern "system" fn Java_chat_vch_flip_NativeClient_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    // Drop media/client outside the registry lock; a slow leave must not block other sessions.
    let removed = sessions()
        .lock()
        .ok()
        .and_then(|mut map| map.remove(&handle));
    drop(removed);
}
