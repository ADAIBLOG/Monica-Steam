use jni::{
    objects::{JClass, JLongArray},
    sys::jintArray,
    JNIEnv,
};
use monica_steam_core::workshop::subscription_order;
use std::ptr;

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeOrderWorkshopSubscriptions(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    scores: JLongArray<'_>,
    ids: JLongArray<'_>,
) -> jintArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let size = env.get_array_length(&scores).ok()?;
        if !(0..=100_000).contains(&size) || env.get_array_length(&ids).ok()? != size {
            return None;
        }
        let mut scores_buffer = vec![0i64; size as usize];
        let mut ids_buffer = vec![0i64; size as usize];
        env.get_long_array_region(&scores, 0, &mut scores_buffer)
            .ok()?;
        env.get_long_array_region(&ids, 0, &mut ids_buffer).ok()?;
        let ids_buffer: Vec<u64> = ids_buffer.into_iter().map(|id| id as u64).collect();
        let order = subscription_order(&scores_buffer, &ids_buffer)?;
        let array = env.new_int_array(size).ok()?;
        env.set_int_array_region(&array, 0, &order).ok()?;
        Some(array.into_raw())
    }));
    result.ok().flatten().unwrap_or(ptr::null_mut())
}
