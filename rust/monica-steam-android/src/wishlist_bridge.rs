use jni::{
    objects::{JByteArray, JClass},
    sys::jbyteArray,
    JNIEnv,
};
use monica_steam_core::wishlist::{parse_wishlist, WishlistItem};
use std::ptr;

const WISHLIST_BRIDGE_MAGIC: &[u8; 4] = b"MSW1";

fn write_required_string(out: &mut Vec<u8>, value: &str) -> Option<()> {
    let bytes = value.as_bytes();
    let length = u32::try_from(bytes.len()).ok()?;
    out.extend_from_slice(&length.to_le_bytes());
    out.extend_from_slice(bytes);
    Some(())
}

fn serialize_wishlist(items: &[WishlistItem]) -> Option<Vec<u8>> {
    let count = u32::try_from(items.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(WISHLIST_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    for item in items {
        out.extend_from_slice(&item.app_id.to_le_bytes());
        match item.package_id {
            Some(package_id) => {
                out.extend_from_slice(&1i32.to_le_bytes());
                out.extend_from_slice(&package_id.to_le_bytes());
            }
            None => {
                out.extend_from_slice(&0i32.to_le_bytes());
                out.extend_from_slice(&0i32.to_le_bytes());
            }
        }
        out.extend_from_slice(&item.discount_percent.to_le_bytes());
        out.extend_from_slice(&item.priority.to_le_bytes());
        out.extend_from_slice(&item.added_at_epoch_seconds.to_le_bytes());
        write_required_string(&mut out, &item.name)?;
        write_required_string(&mut out, &item.image_url)?;
        write_required_string(&mut out, &item.formatted_initial_price)?;
        write_required_string(&mut out, &item.formatted_final_price)?;
    }
    Some(out)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseWishlist(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(items) = parse_wishlist(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_wishlist(&items) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wishlist_bridge_layout_is_stable() {
        let encoded = serialize_wishlist(&[WishlistItem {
            app_id: 620,
            name: "Portal 2".to_string(),
            image_url: "https://cdn.example/capsule.jpg".to_string(),
            package_id: Some(1234),
            discount_percent: 50,
            formatted_initial_price: "¥ 42.00".to_string(),
            formatted_final_price: "¥ 21.00".to_string(),
            priority: 3,
            added_at_epoch_seconds: 1_700_000_000,
        }])
        .unwrap();

        assert_eq!(&encoded[0..4], b"MSW1");
        assert_eq!(u32::from_le_bytes(encoded[4..8].try_into().unwrap()), 1);
        assert_eq!(i32::from_le_bytes(encoded[8..12].try_into().unwrap()), 620);
        assert_eq!(i32::from_le_bytes(encoded[12..16].try_into().unwrap()), 1);
        assert_eq!(i32::from_le_bytes(encoded[16..20].try_into().unwrap()), 1234);
        assert_eq!(i32::from_le_bytes(encoded[20..24].try_into().unwrap()), 50);
        assert_eq!(i32::from_le_bytes(encoded[24..28].try_into().unwrap()), 3);
        assert_eq!(
            i64::from_le_bytes(encoded[28..36].try_into().unwrap()),
            1_700_000_000
        );
    }
}
