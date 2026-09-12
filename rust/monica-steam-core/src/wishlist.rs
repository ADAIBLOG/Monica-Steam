use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};

const STORE_ASSET_BASE: &str = "https://shared.akamai.steamstatic.com/store_item_assets/";
const MAX_WISHLIST_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const MAX_WISHLIST_ITEMS: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WishlistItem {
    pub app_id: i32,
    pub name: String,
    pub image_url: String,
    pub package_id: Option<i32>,
    pub discount_percent: i32,
    pub formatted_initial_price: String,
    pub formatted_final_price: String,
    pub priority: i32,
    pub added_at_epoch_seconds: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WishlistParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManyItems,
}

impl From<ProtoError> for WishlistParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_wishlist(response: &[u8]) -> Result<Vec<WishlistItem>, WishlistParseError> {
    if response.len() > MAX_WISHLIST_RESPONSE_BYTES {
        return Err(WishlistParseError::PayloadTooLarge);
    }
    let fields = parse_all_ref(response)?;
    let candidate_count = fields
        .iter()
        .filter(|field| field.number == 1 && has_kotlin_bytes(field))
        .count();
    if candidate_count > MAX_WISHLIST_ITEMS {
        return Err(WishlistParseError::TooManyItems);
    }

    let mut output = Vec::with_capacity(candidate_count);
    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(parsed) = with_kotlin_bytes(field, parse_wishlist_item) else {
            continue;
        };
        // The Kotlin implementation wraps each wishlist-item protobuf parse in
        // runCatching and drops only the malformed item rather than failing the
        // whole page, so keep that behavior on the native path.
        if let Ok(Some(item)) = parsed {
            output.push(item);
        }
    }
    Ok(output)
}

fn parse_wishlist_item(bytes: &[u8]) -> Result<Option<WishlistItem>, ProtoError> {
    let wishlist = parse_all_ref(bytes)?;
    let store_bytes = last_field(&wishlist, 4).and_then(kotlin_bytes);
    let store_fields = store_bytes
        .and_then(|bytes| parse_all_ref(bytes).ok())
        .unwrap_or_default();

    let wishlist_app_id = last_field(&wishlist, 1).map(kotlin_as_int).unwrap_or(0);
    let store_app_id = last_field(&store_fields, 9).map(kotlin_as_int).unwrap_or(0);
    let app_id = if wishlist_app_id > 0 {
        wishlist_app_id
    } else if store_app_id > 0 {
        store_app_id
    } else {
        return Ok(None);
    };

    let assets = last_field(&store_fields, 30)
        .and_then(kotlin_bytes)
        .and_then(|bytes| parse_all_ref(bytes).ok());
    let asset_format = assets
        .as_deref()
        .and_then(|fields| last_field(fields, 1))
        .map(kotlin_as_string)
        .unwrap_or_default();
    let asset_filename = [2u32, 4, 3].iter().find_map(|number| {
        let value = assets
            .as_deref()
            .and_then(|fields| last_field(fields, *number))
            .map(kotlin_as_string)
            .unwrap_or_default();
        if value.trim().is_empty() {
            None
        } else {
            Some(value)
        }
    });

    // Kotlin picks the first field numbered 40/41 that has bytes, then tries
    // to parse only that one. A malformed first candidate therefore does not
    // fall through to a later purchase entry.
    let purchase_fields = store_fields
        .iter()
        .find(|field| (field.number == 40 || field.number == 41) && has_kotlin_bytes(field))
        .and_then(kotlin_bytes)
        .and_then(|bytes| parse_all_ref(bytes).ok());

    let package_id = purchase_fields
        .as_deref()
        .and_then(|fields| last_field(fields, 1))
        .map(kotlin_as_int)
        .filter(|value| *value > 0);
    let discount_percent = purchase_fields
        .as_deref()
        .and_then(|fields| last_field(fields, 10))
        .map(kotlin_as_int)
        .unwrap_or(0)
        .clamp(0, 100);
    let formatted_initial_price = purchase_fields
        .as_deref()
        .and_then(|fields| last_field(fields, 9))
        .map(kotlin_as_string)
        .unwrap_or_default();
    let formatted_final_price = purchase_fields
        .as_deref()
        .and_then(|fields| last_field(fields, 8))
        .map(kotlin_as_string)
        .unwrap_or_default();

    let raw_name = last_field(&store_fields, 6)
        .map(kotlin_as_string)
        .unwrap_or_default();
    let name = if raw_name.trim().is_empty() {
        format!("App {app_id}")
    } else {
        raw_name
    };

    Ok(Some(WishlistItem {
        app_id,
        name,
        image_url: build_asset_url(app_id, &asset_format, asset_filename.as_deref()),
        package_id,
        discount_percent,
        formatted_initial_price,
        formatted_final_price,
        priority: last_field(&wishlist, 2).map(kotlin_as_int).unwrap_or(0),
        added_at_epoch_seconds: last_field(&wishlist, 3)
            .map(kotlin_as_long)
            .unwrap_or(0),
    }))
}

fn build_asset_url(app_id: i32, format: &str, filename: Option<&str>) -> String {
    if !format.trim().is_empty() {
        if let Some(filename) = filename.filter(|value| !value.trim().is_empty()) {
            let resolved = format.replace("${FILENAME}", filename);
            if resolved.starts_with("https://") {
                return resolved;
            }
            return format!("{STORE_ASSET_BASE}{}", resolved.trim_start_matches('/'));
        }
    }
    format!("{STORE_ASSET_BASE}steam/apps/{app_id}/header.jpg")
}

fn last_field<'fields, 'data>(
    fields: &'fields [ProtoFieldRef<'data>],
    number: u32,
) -> Option<&'fields ProtoFieldRef<'data>> {
    fields.iter().rev().find(|field| field.number == number)
}

fn kotlin_as_int(field: &ProtoFieldRef<'_>) -> i32 {
    kotlin_as_long(field) as i32
}

fn kotlin_as_long(field: &ProtoFieldRef<'_>) -> i64 {
    match field.value {
        ProtoValueRef::Varint(value) => value as i64,
        _ => 0,
    }
}

fn kotlin_as_string(field: &ProtoFieldRef<'_>) -> String {
    kotlin_bytes(field)
        .map(|bytes| String::from_utf8_lossy(bytes).into_owned())
        .unwrap_or_default()
}

fn has_kotlin_bytes(field: &ProtoFieldRef<'_>) -> bool {
    !matches!(field.value, ProtoValueRef::Varint(_))
}

fn kotlin_bytes<'data>(field: &ProtoFieldRef<'data>) -> Option<&'data [u8]> {
    match field.value {
        ProtoValueRef::Bytes(bytes) => Some(bytes),
        // SteamProtoReader materializes fixed-width values as ByteArray too,
        // but these temporary arrays do not borrow from `field`. Callers only
        // need nested-message bytes here, so fixed-width fields are represented
        // through `with_kotlin_bytes` where the temporary lifetime is scoped.
        ProtoValueRef::Varint(_) | ProtoValueRef::Fixed32(_) | ProtoValueRef::Fixed64(_) => None,
    }
}

fn with_kotlin_bytes<T>(field: &ProtoFieldRef<'_>, f: impl FnOnce(&[u8]) -> T) -> Option<T> {
    match field.value {
        ProtoValueRef::Varint(_) => None,
        ProtoValueRef::Bytes(bytes) => Some(f(bytes)),
        ProtoValueRef::Fixed32(value) => {
            let bytes = value.to_le_bytes();
            Some(f(&bytes))
        }
        ProtoValueRef::Fixed64(value) => {
            let bytes = value.to_le_bytes();
            Some(f(&bytes))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ProtoWriter;

    fn purchase(package_id: i64, initial: &str, final_price: &str, discount: i64) -> ProtoWriter {
        let mut value = ProtoWriter::new();
        value.write_varint(1, package_id).unwrap();
        value.write_string(8, final_price).unwrap();
        value.write_string(9, initial).unwrap();
        value.write_varint(10, discount).unwrap();
        value
    }

    fn store(app_id: i64, name: &str) -> ProtoWriter {
        let mut assets = ProtoWriter::new();
        assets.write_string(1, "apps/620/${FILENAME}").unwrap();
        assets.write_string(2, "capsule.jpg").unwrap();

        let mut store = ProtoWriter::new();
        store.write_varint(9, app_id).unwrap();
        store.write_string(6, name).unwrap();
        store.write_message(30, &assets).unwrap();
        store
    }

    fn wishlist(app_id: Option<i64>, store: &ProtoWriter) -> ProtoWriter {
        let mut item = ProtoWriter::new();
        if let Some(app_id) = app_id {
            item.write_varint(1, app_id).unwrap();
        }
        item.write_varint(2, 3).unwrap();
        item.write_varint(3, 1_700_000_000).unwrap();
        item.write_message(4, store).unwrap();
        item
    }

    #[test]
    fn parses_store_assets_purchase_and_localized_prices() {
        let mut store = store(620, "Portal 2");
        store
            .write_message(40, &purchase(1234, "¥ 42.00", "¥ 21.00", 50))
            .unwrap();
        let item = wishlist(Some(620), &store);
        let mut response = ProtoWriter::new();
        response.write_message(1, &item).unwrap();

        let parsed = parse_wishlist(response.as_bytes()).unwrap();
        assert_eq!(parsed.len(), 1);
        let item = &parsed[0];
        assert_eq!(item.app_id, 620);
        assert_eq!(item.name, "Portal 2");
        assert_eq!(
            item.image_url,
            "https://shared.akamai.steamstatic.com/store_item_assets/apps/620/capsule.jpg"
        );
        assert_eq!(item.package_id, Some(1234));
        assert_eq!(item.discount_percent, 50);
        assert_eq!(item.formatted_initial_price, "¥ 42.00");
        assert_eq!(item.formatted_final_price, "¥ 21.00");
        assert_eq!(item.priority, 3);
        assert_eq!(item.added_at_epoch_seconds, 1_700_000_000);
    }

    #[test]
    fn falls_back_to_store_app_id_default_name_and_header_asset() {
        let store = store(570, "   ");
        let item = wishlist(None, &store);
        let mut response = ProtoWriter::new();
        response.write_message(1, &item).unwrap();

        let parsed = parse_wishlist(response.as_bytes()).unwrap();
        assert_eq!(parsed[0].app_id, 570);
        assert_eq!(parsed[0].name, "App 570");
        assert_eq!(
            parsed[0].image_url,
            "https://shared.akamai.steamstatic.com/store_item_assets/apps/620/capsule.jpg"
        );
    }

    #[test]
    fn nested_maps_keep_last_fields_and_discount_is_clamped() {
        let mut store = store(620, "Old");
        store.write_string(6, "New").unwrap();
        let mut p = purchase(1, "A", "B", 150);
        p.write_varint(1, 2).unwrap();
        store.write_message(40, &p).unwrap();
        let mut item = wishlist(Some(620), &store);
        item.write_varint(2, 9).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &item).unwrap();

        let parsed = parse_wishlist(response.as_bytes()).unwrap();
        assert_eq!(parsed[0].name, "New");
        assert_eq!(parsed[0].package_id, Some(2));
        assert_eq!(parsed[0].discount_percent, 100);
        assert_eq!(parsed[0].priority, 9);
    }

    #[test]
    fn malformed_item_is_skipped_without_dropping_valid_items() {
        let store = store(620, "Portal 2");
        let valid = wishlist(Some(620), &store);
        let mut response = ProtoWriter::new();
        response.write_bytes(1, &[0x08, 0x80]).unwrap();
        response.write_message(1, &valid).unwrap();

        let parsed = parse_wishlist(response.as_bytes()).unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].app_id, 620);
    }

    #[test]
    fn item_without_usable_app_id_is_filtered() {
        let store = store(0, "Unknown");
        let item = wishlist(Some(0), &store);
        let mut response = ProtoWriter::new();
        response.write_message(1, &item).unwrap();
        assert!(parse_wishlist(response.as_bytes()).unwrap().is_empty());
    }
}
