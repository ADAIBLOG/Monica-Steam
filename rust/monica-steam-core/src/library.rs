use crate::proto::{
    decode_packed_varints, parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef,
};
use std::collections::HashMap;

const MAX_LIBRARY_RESPONSE_BYTES: usize = 64 * 1024 * 1024;
const MAX_LIBRARY_ITEMS: usize = 100_000;
const STEAM_CLOUD_CATEGORY_ID: i32 = 23;
const STORE_ASSET_BASE: &str = "https://shared.akamai.steamstatic.com/store_item_assets/";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OwnedGame {
    pub app_id: i32,
    pub name: String,
    pub playtime_recent_minutes: i32,
    pub playtime_forever_minutes: i32,
    pub icon_hash: String,
    pub last_played_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AchievementProgress {
    pub app_id: i32,
    pub unlocked: i32,
    pub total: i32,
    pub all_unlocked: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoreMetadata {
    pub app_id: i32,
    pub header_image_url: String,
    pub final_price_minor: Option<i64>,
    pub original_price_minor: Option<i64>,
    pub supports_steam_cloud: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LibraryParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    InvalidResponse,
    TooManyItems,
}

impl From<ProtoError> for LibraryParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_owned_games(response: &[u8]) -> Result<Vec<OwnedGame>, LibraryParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    if fields.is_empty() {
        return Err(LibraryParseError::InvalidResponse);
    }

    // Kotlin uses firstOrNull { field == 1 && wireType == 0 } for the declared
    // count, while game entries are every field 2 whose `bytes` property is set.
    let declared_count = fields
        .iter()
        .find(|field| field.number == 1 && field.wire_type() == 0)
        .map(kotlin_as_long)
        .map(|value| value as i32);
    let game_field_count = fields
        .iter()
        .filter(|field| field.number == 2 && has_kotlin_bytes(field))
        .count();
    if game_field_count > MAX_LIBRARY_ITEMS {
        return Err(LibraryParseError::TooManyItems);
    }
    if declared_count.is_some_and(|count| count != game_field_count as i32) {
        return Err(LibraryParseError::InvalidResponse);
    }

    let mut games = Vec::with_capacity(game_field_count);
    for field in fields.iter().filter(|field| field.number == 2) {
        let Some(parsed) = with_kotlin_bytes(field, |bytes| {
            let Ok(game_fields) = parse_all_ref(bytes) else {
                return None;
            };
            let app_id_field = last_field(&game_fields, 1)?;
            let app_id = kotlin_as_long(app_id_field) as i32;
            let raw_name = last_kotlin_string(&game_fields, 2);
            let name = if is_kotlin_blank(&raw_name) {
                format!("App {app_id}")
            } else {
                raw_name
            };
            Some(OwnedGame {
                app_id,
                name,
                playtime_recent_minutes: last_kotlin_i64_or_zero(&game_fields, 3).max(0) as i32,
                playtime_forever_minutes: last_kotlin_i64_or_zero(&game_fields, 4).max(0)
                    as i32,
                icon_hash: last_kotlin_string(&game_fields, 5),
                last_played_at: last_kotlin_i64_or_zero(&game_fields, 11).max(0),
            })
        }) else {
            continue;
        };
        if let Some(game) = parsed {
            games.push(game);
        }
    }
    Ok(games)
}

pub fn parse_achievement_progress(
    response: &[u8],
) -> Result<Vec<AchievementProgress>, LibraryParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let item_count = fields
        .iter()
        .filter(|field| field.number == 1 && has_kotlin_bytes(field))
        .count();
    if item_count > MAX_LIBRARY_ITEMS {
        return Err(LibraryParseError::TooManyItems);
    }

    // Kotlin's Sequence<Pair>.toMap() keeps the first insertion position for a
    // duplicate key but replaces its value with the last occurrence. Track the
    // first vector index and update in place to keep exactly that behavior.
    let mut progress = Vec::with_capacity(item_count);
    let mut indices = HashMap::<i32, usize>::with_capacity(item_count);
    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(parsed) = with_kotlin_bytes(field, |bytes| {
            let Ok(item) = parse_all_ref(bytes) else {
                return None;
            };
            let app_id_field = last_field(&item, 1)?;
            let app_id = kotlin_as_long(app_id_field) as i32;
            if app_id <= 0 {
                return None;
            }
            let unlocked = last_kotlin_i64_or_zero(&item, 2).max(0) as i32;
            let total = last_kotlin_i64_or_zero(&item, 3).max(0) as i32;
            let all_unlocked = last_kotlin_i64_or_zero(&item, 5) != 0
                || (total > 0 && unlocked >= total);
            Some(AchievementProgress {
                app_id,
                unlocked,
                total,
                all_unlocked,
            })
        }) else {
            continue;
        };
        let Some(parsed) = parsed else {
            continue;
        };
        if let Some(index) = indices.get(&parsed.app_id).copied() {
            progress[index] = parsed;
        } else {
            indices.insert(parsed.app_id, progress.len());
            progress.push(parsed);
        }
    }
    Ok(progress)
}

pub fn parse_store_items(response: &[u8]) -> Result<Vec<StoreMetadata>, LibraryParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let item_count = fields
        .iter()
        .filter(|field| field.number == 1 && has_kotlin_bytes(field))
        .count();
    if item_count > MAX_LIBRARY_ITEMS {
        return Err(LibraryParseError::TooManyItems);
    }

    let mut metadata = Vec::with_capacity(item_count);
    let mut indices = HashMap::<i32, usize>::with_capacity(item_count);
    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(parsed) = with_kotlin_bytes(field, parse_store_item) else {
            continue;
        };
        let Some(parsed) = parsed? else {
            continue;
        };
        if let Some(index) = indices.get(&parsed.app_id).copied() {
            metadata[index] = parsed;
        } else {
            indices.insert(parsed.app_id, metadata.len());
            metadata.push(parsed);
        }
    }
    Ok(metadata)
}

fn parse_store_item(bytes: &[u8]) -> Result<Option<StoreMetadata>, LibraryParseError> {
    // Kotlin catches only the top-level per-item parse. Corrupt one item and the
    // remaining StoreBrowse entries still survive; corrupt nested assets/categories
    // or purchase messages escape that runCatching and fail the batch.
    let Ok(item) = parse_all_ref(bytes) else {
        return Ok(None);
    };
    let Some(app_id_field) = last_field(&item, 9) else {
        return Ok(None);
    };
    let app_id = kotlin_as_long(app_id_field) as i32;
    if app_id <= 0 {
        return Ok(None);
    }

    let (asset_format, asset_filename) = match last_field(&item, 30) {
        Some(field) => match with_kotlin_bytes(field, |bytes| {
            let assets = parse_all_ref(bytes)?;
            let format = last_kotlin_string(&assets, 1);
            let filename = [4u32, 2, 3]
                .into_iter()
                .map(|number| last_kotlin_string(&assets, number))
                .find(|value| !is_kotlin_blank(value));
            Ok::<_, ProtoError>((format, filename))
        }) {
            Some(result) => result?,
            None => (String::new(), None),
        },
        None => (String::new(), None),
    };
    let header_image_url = build_store_asset_url(&asset_format, asset_filename.as_deref());

    let supports_steam_cloud = match last_field(&item, 22) {
        Some(field) => match with_kotlin_bytes(field, |bytes| {
            let categories = parse_all_ref(bytes)?;
            for category in categories.iter().filter(|field| field.number == 3) {
                if category.wire_type() == 0 {
                    if kotlin_as_long(category) as i32 == STEAM_CLOUD_CATEGORY_ID {
                        return Ok(true);
                    }
                    continue;
                }
                if let Some(result) = with_kotlin_bytes(category, |bytes| {
                    Ok::<_, ProtoError>(
                        decode_packed_varints(bytes)?
                            .into_iter()
                            .any(|value| value as i32 == STEAM_CLOUD_CATEGORY_ID),
                    )
                }) {
                    if result? {
                        return Ok(true);
                    }
                }
            }
            Ok::<_, ProtoError>(false)
        }) {
            Some(result) => Some(result?),
            None => None,
        },
        None => None,
    };

    let is_free = last_field(&item, 13)
        .map(kotlin_as_long)
        .is_some_and(|value| value != 0);
    let purchase_values = match last_field(&item, 40) {
        Some(field) => match with_kotlin_bytes(field, |bytes| {
            let purchase = parse_all_ref(bytes)?;
            let final_price = last_field(&purchase, 5)
                .map(kotlin_as_long)
                .map(|value| value.max(0));
            let original_price = last_field(&purchase, 6)
                .map(kotlin_as_long)
                .map(|value| value.max(0));
            Ok::<_, ProtoError>((final_price, original_price))
        }) {
            Some(result) => result?,
            None => (None, None),
        },
        None => (None, None),
    };

    let (final_price_minor, original_price_minor) = if is_free {
        (Some(0), Some(0))
    } else if let Some(final_price) = purchase_values.0 {
        (
            Some(final_price),
            Some(purchase_values.1.unwrap_or(final_price)),
        )
    } else {
        (None, None)
    };

    Ok(Some(StoreMetadata {
        app_id,
        header_image_url,
        final_price_minor,
        original_price_minor,
        supports_steam_cloud,
    }))
}

fn build_store_asset_url(format: &str, filename: Option<&str>) -> String {
    let Some(filename) = filename.filter(|value| !is_kotlin_blank(value)) else {
        return String::new();
    };
    if is_kotlin_blank(format) {
        return String::new();
    }
    let resolved = format.replace("${FILENAME}", filename);
    if resolved.starts_with("https://") {
        resolved
    } else {
        format!("{STORE_ASSET_BASE}{}", resolved.trim_start_matches('/'))
    }
}

fn ensure_payload_size(response: &[u8]) -> Result<(), LibraryParseError> {
    if response.len() > MAX_LIBRARY_RESPONSE_BYTES {
        Err(LibraryParseError::PayloadTooLarge)
    } else {
        Ok(())
    }
}

fn last_field<'fields, 'data>(
    fields: &'fields [ProtoFieldRef<'data>],
    number: u32,
) -> Option<&'fields ProtoFieldRef<'data>> {
    fields.iter().rev().find(|field| field.number == number)
}

fn kotlin_as_long(field: &ProtoFieldRef<'_>) -> i64 {
    match field.value {
        ProtoValueRef::Varint(value) => value as i64,
        _ => 0,
    }
}

fn last_kotlin_i64_or_zero(fields: &[ProtoFieldRef<'_>], number: u32) -> i64 {
    last_field(fields, number).map(kotlin_as_long).unwrap_or(0)
}

fn last_kotlin_string(fields: &[ProtoFieldRef<'_>], number: u32) -> String {
    last_field(fields, number)
        .and_then(|field| {
            with_kotlin_bytes(field, |bytes| String::from_utf8_lossy(bytes).into_owned())
        })
        .unwrap_or_default()
}

fn has_kotlin_bytes(field: &ProtoFieldRef<'_>) -> bool {
    !matches!(field.value, ProtoValueRef::Varint(_))
}

fn with_kotlin_bytes<T>(field: &ProtoFieldRef<'_>, f: impl FnOnce(&[u8]) -> T) -> Option<T> {
    match field.value {
        ProtoValueRef::Varint(_) => None,
        ProtoValueRef::Bytes(bytes) => Some(f(bytes)),
        ProtoValueRef::Fixed64(value) => {
            let bytes = value.to_le_bytes();
            Some(f(&bytes))
        }
        ProtoValueRef::Fixed32(value) => {
            let bytes = value.to_le_bytes();
            Some(f(&bytes))
        }
    }
}

fn is_kotlin_blank(value: &str) -> bool {
    value.chars().all(char::is_whitespace)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ProtoWriter;

    fn game(
        app_id: i64,
        name: &str,
        recent: i64,
        forever: i64,
        icon: &str,
        last_played: i64,
    ) -> ProtoWriter {
        let mut writer = ProtoWriter::new();
        writer.write_varint(1, app_id).unwrap();
        writer.write_string(2, name).unwrap();
        writer.write_varint(3, recent).unwrap();
        writer.write_varint(4, forever).unwrap();
        writer.write_string(5, icon).unwrap();
        writer.write_varint(11, last_played).unwrap();
        writer
    }

    fn achievement_progress(
        app_id: i64,
        unlocked: i64,
        total: i64,
        all_unlocked: bool,
    ) -> ProtoWriter {
        let mut writer = ProtoWriter::new();
        writer.write_varint(1, app_id).unwrap();
        writer.write_varint(2, unlocked).unwrap();
        writer.write_varint(3, total).unwrap();
        writer.write_bool(5, all_unlocked).unwrap();
        writer
    }

    fn store_item(
        app_id: i64,
        asset_format: &str,
        header: &str,
        final_price: Option<i64>,
        original_price: Option<i64>,
        is_free: bool,
        cloud_values: Option<&[i64]>,
    ) -> ProtoWriter {
        let mut item = ProtoWriter::new();
        item.write_varint(9, app_id).unwrap();
        item.write_bool(13, is_free).unwrap();

        let mut assets = ProtoWriter::new();
        assets.write_string(1, asset_format).unwrap();
        assets.write_string(4, header).unwrap();
        item.write_message(30, &assets).unwrap();

        if let Some(values) = cloud_values {
            let mut categories = ProtoWriter::new();
            categories
                .write_packed_varints(3, values.iter().copied())
                .unwrap();
            item.write_message(22, &categories).unwrap();
        }

        if final_price.is_some() || original_price.is_some() {
            let mut purchase = ProtoWriter::new();
            if let Some(value) = final_price {
                purchase.write_varint(5, value).unwrap();
            }
            if let Some(value) = original_price {
                purchase.write_varint(6, value).unwrap();
            }
            item.write_message(40, &purchase).unwrap();
        }
        item
    }

    #[test]
    fn owned_games_match_kotlin_layout_and_order() {
        let first = game(570, "Dota 2", 15, 1200, "abc", 1_700_000_000);
        let second = game(730, "Counter-Strike 2", 30, 2400, "def", 1_710_000_000);
        let mut response = ProtoWriter::new();
        response.write_varint(1, 2).unwrap();
        response.write_message(2, &first).unwrap();
        response.write_message(2, &second).unwrap();

        let games = parse_owned_games(response.as_bytes()).unwrap();
        assert_eq!(games.len(), 2);
        assert_eq!(games[0].app_id, 570);
        assert_eq!(games[0].name, "Dota 2");
        assert_eq!(games[0].playtime_forever_minutes, 1200);
        assert_eq!(games[0].icon_hash, "abc");
        assert_eq!(games[1].app_id, 730);
        assert_eq!(games[1].last_played_at, 1_710_000_000);
    }

    #[test]
    fn declared_count_mismatch_is_invalid() {
        let first = game(570, "Dota 2", 0, 0, "", 0);
        let mut response = ProtoWriter::new();
        response.write_varint(1, 2).unwrap();
        response.write_message(2, &first).unwrap();
        assert_eq!(
            parse_owned_games(response.as_bytes()),
            Err(LibraryParseError::InvalidResponse)
        );
    }

    #[test]
    fn malformed_nested_game_is_skipped_after_count_validation() {
        let valid = game(570, "Dota 2", 0, 1, "icon", 2);
        let mut response = ProtoWriter::new();
        response.write_varint(1, 2).unwrap();
        response.write_bytes(2, &[0x08, 0x80]).unwrap();
        response.write_message(2, &valid).unwrap();

        let games = parse_owned_games(response.as_bytes()).unwrap();
        assert_eq!(games.len(), 1);
        assert_eq!(games[0].app_id, 570);
    }

    #[test]
    fn nested_parse_uses_last_duplicate_field_like_kotlin_associate_by() {
        let mut duplicate = ProtoWriter::new();
        duplicate.write_varint(1, 1).unwrap();
        duplicate.write_varint(1, 570).unwrap();
        duplicate.write_string(2, "old").unwrap();
        duplicate.write_string(2, "new").unwrap();
        duplicate.write_varint(4, 1).unwrap();
        duplicate.write_varint(4, 99).unwrap();

        let mut response = ProtoWriter::new();
        response.write_varint(1, 1).unwrap();
        response.write_message(2, &duplicate).unwrap();
        let games = parse_owned_games(response.as_bytes()).unwrap();
        assert_eq!(games[0].app_id, 570);
        assert_eq!(games[0].name, "new");
        assert_eq!(games[0].playtime_forever_minutes, 99);
    }

    #[test]
    fn kotlin_as_long_does_not_treat_fixed_fields_as_varints() {
        let mut encoded = ProtoWriter::new();
        encoded.write_fixed32(1, 570).unwrap();
        encoded.write_fixed64(4, 99).unwrap();
        encoded.write_string(2, "Fixed fields").unwrap();
        let mut response = ProtoWriter::new();
        response.write_varint(1, 1).unwrap();
        response.write_message(2, &encoded).unwrap();

        let game = &parse_owned_games(response.as_bytes()).unwrap()[0];
        assert_eq!(game.app_id, 0);
        assert_eq!(game.playtime_forever_minutes, 0);
    }

    #[test]
    fn blank_name_and_negative_times_match_kotlin_fallback() {
        let blank = game(570, "   ", -1, -2, "", -3);
        let mut response = ProtoWriter::new();
        response.write_varint(1, 1).unwrap();
        response.write_message(2, &blank).unwrap();
        let game = &parse_owned_games(response.as_bytes()).unwrap()[0];
        assert_eq!(game.name, "App 570");
        assert_eq!(game.playtime_recent_minutes, 0);
        assert_eq!(game.playtime_forever_minutes, 0);
        assert_eq!(game.last_played_at, 0);
    }

    #[test]
    fn empty_owned_games_response_is_invalid() {
        assert_eq!(
            parse_owned_games(&[]),
            Err(LibraryParseError::InvalidResponse)
        );
    }

    #[test]
    fn achievement_progress_matches_kotlin_completion_rules() {
        let mut response = ProtoWriter::new();
        response
            .write_message(1, &achievement_progress(730, 10, 10, false))
            .unwrap();
        response
            .write_message(1, &achievement_progress(570, 5, 12, false))
            .unwrap();
        response
            .write_message(1, &achievement_progress(440, 0, 0, true))
            .unwrap();

        let progress = parse_achievement_progress(response.as_bytes()).unwrap();
        assert_eq!(progress.len(), 3);
        assert!(progress[0].all_unlocked);
        assert!(!progress[1].all_unlocked);
        assert!(progress[2].all_unlocked);
    }

    #[test]
    fn achievement_progress_duplicate_app_keeps_first_position_and_last_value() {
        let mut response = ProtoWriter::new();
        response
            .write_message(1, &achievement_progress(730, 1, 10, false))
            .unwrap();
        response
            .write_message(1, &achievement_progress(570, 2, 10, false))
            .unwrap();
        response
            .write_message(1, &achievement_progress(730, 10, 10, true))
            .unwrap();

        let progress = parse_achievement_progress(response.as_bytes()).unwrap();
        assert_eq!(progress.len(), 2);
        assert_eq!(progress[0].app_id, 730);
        assert_eq!(progress[0].unlocked, 10);
        assert!(progress[0].all_unlocked);
        assert_eq!(progress[1].app_id, 570);
    }

    #[test]
    fn malformed_achievement_item_is_skipped() {
        let valid = achievement_progress(730, 3, 10, false);
        let mut response = ProtoWriter::new();
        response.write_bytes(1, &[0x08, 0x80]).unwrap();
        response.write_message(1, &valid).unwrap();
        let progress = parse_achievement_progress(response.as_bytes()).unwrap();
        assert_eq!(progress.len(), 1);
        assert_eq!(progress[0].app_id, 730);
    }

    #[test]
    fn store_items_parse_header_price_and_cloud_category() {
        let item = store_item(
            1_718_570,
            "steam/apps/1718570/${FILENAME}?t=1770740786",
            "header_schinese.jpg",
            Some(8_000),
            Some(8_800),
            false,
            Some(&[1, 23, 42]),
        );
        let mut response = ProtoWriter::new();
        response.write_message(1, &item).unwrap();

        let result = parse_store_items(response.as_bytes()).unwrap();
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].app_id, 1_718_570);
        assert_eq!(
            result[0].header_image_url,
            "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1718570/header_schinese.jpg?t=1770740786"
        );
        assert_eq!(result[0].final_price_minor, Some(8_000));
        assert_eq!(result[0].original_price_minor, Some(8_800));
        assert_eq!(result[0].supports_steam_cloud, Some(true));
    }

    #[test]
    fn store_items_free_and_missing_original_match_kotlin() {
        let free = store_item(
            730,
            "steam/apps/730/${FILENAME}",
            "header.jpg",
            None,
            None,
            true,
            None,
        );
        let paid = store_item(
            10,
            "https://cdn.example/${FILENAME}",
            "header.jpg",
            Some(2_680),
            None,
            false,
            Some(&[5]),
        );
        let mut response = ProtoWriter::new();
        response.write_message(1, &free).unwrap();
        response.write_message(1, &paid).unwrap();

        let result = parse_store_items(response.as_bytes()).unwrap();
        assert_eq!(result[0].final_price_minor, Some(0));
        assert_eq!(result[0].original_price_minor, Some(0));
        assert_eq!(result[0].supports_steam_cloud, None);
        assert_eq!(result[1].final_price_minor, Some(2_680));
        assert_eq!(result[1].original_price_minor, Some(2_680));
        assert_eq!(result[1].supports_steam_cloud, Some(false));
        assert_eq!(result[1].header_image_url, "https://cdn.example/header.jpg");
    }

    #[test]
    fn store_items_duplicate_app_keeps_first_position_and_last_value() {
        let old = store_item(10, "a/${FILENAME}", "old.jpg", Some(100), None, false, None);
        let other = store_item(20, "b/${FILENAME}", "other.jpg", Some(200), None, false, None);
        let new = store_item(10, "a/${FILENAME}", "new.jpg", Some(300), None, false, None);
        let mut response = ProtoWriter::new();
        response.write_message(1, &old).unwrap();
        response.write_message(1, &other).unwrap();
        response.write_message(1, &new).unwrap();

        let result = parse_store_items(response.as_bytes()).unwrap();
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].app_id, 10);
        assert_eq!(result[0].final_price_minor, Some(300));
        assert!(result[0].header_image_url.ends_with("new.jpg"));
        assert_eq!(result[1].app_id, 20);
    }

    #[test]
    fn malformed_store_item_is_skipped_but_nested_corruption_fails_batch() {
        let valid = store_item(10, "a/${FILENAME}", "ok.jpg", Some(100), None, false, None);
        let mut response = ProtoWriter::new();
        response.write_bytes(1, &[0x08, 0x80]).unwrap();
        response.write_message(1, &valid).unwrap();
        let result = parse_store_items(response.as_bytes()).unwrap();
        assert_eq!(result.len(), 1);

        let mut corrupt_nested = ProtoWriter::new();
        corrupt_nested.write_varint(9, 10).unwrap();
        corrupt_nested.write_bytes(30, &[0x08, 0x80]).unwrap();
        let mut nested_response = ProtoWriter::new();
        nested_response.write_message(1, &corrupt_nested).unwrap();
        assert!(matches!(
            parse_store_items(nested_response.as_bytes()),
            Err(LibraryParseError::Proto(_))
        ));
    }
}
