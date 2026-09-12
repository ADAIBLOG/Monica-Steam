use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};
use std::collections::HashMap;

const STEAM_ID64_OFFSET: i64 = 76_561_197_960_265_728;
const ITEM_IMAGE_BASE: &str = "https://community.fastly.steamstatic.com/economy/image/";
const MAX_TRADE_RESPONSE_BYTES: usize = 32 * 1024 * 1024;
const MAX_TRADE_OFFERS: usize = 100_000;
const MAX_TRADE_ITEMS: usize = 1_000_000;
const MAX_DESCRIPTIONS: usize = 1_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TradeOfferDirection {
    Sent,
    Received,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TradeOfferItem {
    pub app_id: i32,
    pub context_id: u64,
    pub asset_id: u64,
    pub class_id: u64,
    pub instance_id: u64,
    pub amount: i32,
    pub name: String,
    pub item_type: String,
    pub icon_url: String,
    pub tradable: bool,
    pub marketable: bool,
    pub missing: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TradeOffer {
    pub id: u64,
    pub direction: TradeOfferDirection,
    pub partner_account_id: i64,
    pub message: String,
    pub state_code: i32,
    pub items_to_give: Vec<TradeOfferItem>,
    pub items_to_receive: Vec<TradeOfferItem>,
    pub created_at: i64,
    pub updated_at: i64,
    pub expiration_time: i64,
    pub escrow_end_date: i64,
    pub confirmation_method: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TradeOffersSnapshot {
    pub received: Vec<TradeOffer>,
    pub sent: Vec<TradeOffer>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TradeOfferParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManyOffers,
    TooManyItems,
    TooManyDescriptions,
}

impl From<ProtoError> for TradeOfferParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct DescriptionKey {
    app_id: i32,
    class_id: u64,
    instance_id: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct TradeDescription {
    name: String,
    item_type: String,
    icon_url: String,
    tradable: bool,
    marketable: bool,
}

pub fn parse_trade_offers(response: &[u8]) -> Result<TradeOffersSnapshot, TradeOfferParseError> {
    if response.len() > MAX_TRADE_RESPONSE_BYTES {
        return Err(TradeOfferParseError::PayloadTooLarge);
    }
    let fields = parse_all_ref(response)?;

    let description_count = fields
        .iter()
        .filter(|field| field.number == 3 && matches!(field.value, ProtoValueRef::Bytes(_)))
        .count();
    if description_count > MAX_DESCRIPTIONS {
        return Err(TradeOfferParseError::TooManyDescriptions);
    }
    let mut descriptions = HashMap::with_capacity(description_count);
    for field in fields.iter().filter(|field| field.number == 3) {
        let ProtoValueRef::Bytes(bytes) = field.value else {
            continue;
        };
        let (key, description) = parse_description(bytes)?;
        descriptions.insert(key, description);
    }

    let offer_count = fields
        .iter()
        .filter(|field| {
            (field.number == 1 || field.number == 2)
                && matches!(field.value, ProtoValueRef::Bytes(_))
        })
        .count();
    if offer_count > MAX_TRADE_OFFERS {
        return Err(TradeOfferParseError::TooManyOffers);
    }

    let mut total_items = 0usize;
    let mut sent = Vec::new();
    let mut received = Vec::new();
    for field in &fields {
        let direction = match field.number {
            1 => TradeOfferDirection::Sent,
            2 => TradeOfferDirection::Received,
            _ => continue,
        };
        let ProtoValueRef::Bytes(bytes) = field.value else {
            continue;
        };
        if let Some(offer) = parse_offer(bytes, direction, &descriptions, &mut total_items)? {
            match direction {
                TradeOfferDirection::Sent => sent.push(offer),
                TradeOfferDirection::Received => received.push(offer),
            }
        }
    }

    // Kotlin's sortedByDescending is stable. Rust's slice sort is stable too,
    // so equal timestamps retain the protobuf source order.
    sent.sort_by_key(|offer| std::cmp::Reverse(max_timestamp(offer)));
    received.sort_by_key(|offer| std::cmp::Reverse(max_timestamp(offer)));

    Ok(TradeOffersSnapshot { received, sent })
}

fn parse_offer(
    bytes: &[u8],
    direction: TradeOfferDirection,
    descriptions: &HashMap<DescriptionKey, TradeDescription>,
    total_items: &mut usize,
) -> Result<Option<TradeOffer>, TradeOfferParseError> {
    let fields = parse_all_ref(bytes)?;
    let first = |number| fields.iter().find(|field| field.number == number);

    let Some(id_field) = first(1) else {
        return Ok(None);
    };
    let id = kotlin_as_u64_varint(id_field);
    if id == 0 {
        return Ok(None);
    }

    let mut items_to_give = Vec::new();
    let mut items_to_receive = Vec::new();
    for field in &fields {
        let target = match field.number {
            6 => &mut items_to_give,
            7 => &mut items_to_receive,
            _ => continue,
        };
        let ProtoValueRef::Bytes(item_bytes) = field.value else {
            continue;
        };
        if *total_items >= MAX_TRADE_ITEMS {
            return Err(TradeOfferParseError::TooManyItems);
        }
        *total_items += 1;
        target.push(parse_asset(item_bytes, descriptions)?);
    }

    Ok(Some(TradeOffer {
        id,
        direction,
        partner_account_id: first(2).map(kotlin_as_long).unwrap_or(0),
        message: first(3).map(kotlin_as_string).unwrap_or_default(),
        state_code: first(5).map(kotlin_as_int).unwrap_or(0),
        items_to_give,
        items_to_receive,
        created_at: first(9).map(kotlin_as_long).unwrap_or(0),
        updated_at: first(10).map(kotlin_as_long).unwrap_or(0),
        expiration_time: first(4).map(kotlin_as_long).unwrap_or(0),
        escrow_end_date: first(13).map(kotlin_as_long).unwrap_or(0),
        confirmation_method: first(14).map(kotlin_as_int).unwrap_or(0),
    }))
}

fn parse_asset(
    bytes: &[u8],
    descriptions: &HashMap<DescriptionKey, TradeDescription>,
) -> Result<TradeOfferItem, TradeOfferParseError> {
    let fields = parse_all_ref(bytes)?;
    let last = |number| fields.iter().rev().find(|field| field.number == number);
    let app_id = last(1).map(kotlin_as_int).unwrap_or(0);
    let class_id = last(4).map(kotlin_as_u64_varint).unwrap_or(0);
    let instance_id = last(5).map(kotlin_as_u64_varint).unwrap_or(0);
    let description = descriptions
        .get(&DescriptionKey { app_id, class_id, instance_id })
        .or_else(|| descriptions.get(&DescriptionKey { app_id, class_id, instance_id: 0 }));
    let raw_amount = last(7).map(kotlin_as_long).unwrap_or(1).max(1);

    Ok(TradeOfferItem {
        app_id,
        context_id: last(2).map(kotlin_as_u64_varint).unwrap_or(0),
        asset_id: last(3).map(kotlin_as_u64_varint).unwrap_or(0),
        class_id,
        instance_id,
        amount: raw_amount as i32,
        name: description.map(|value| value.name.clone()).unwrap_or_default(),
        item_type: description
            .map(|value| value.item_type.clone())
            .unwrap_or_default(),
        icon_url: description
            .map(|value| value.icon_url.clone())
            .unwrap_or_default(),
        tradable: description.map(|value| value.tradable).unwrap_or(false),
        marketable: description.map(|value| value.marketable).unwrap_or(false),
        missing: last(8).map(kotlin_as_bool).unwrap_or(false),
    })
}

fn parse_description(
    bytes: &[u8],
) -> Result<(DescriptionKey, TradeDescription), TradeOfferParseError> {
    let fields = parse_all_ref(bytes)?;
    let last = |number| fields.iter().rev().find(|field| field.number == number);
    let app_id = last(1).map(kotlin_as_int).unwrap_or(0);
    let class_id = last(2).map(kotlin_as_u64_varint).unwrap_or(0);
    let instance_id = last(3).map(kotlin_as_u64_varint).unwrap_or(0);
    let raw_icon = last(6).map(kotlin_as_string).unwrap_or_default();
    let primary_name = last(14).map(kotlin_as_string).unwrap_or_default();
    let fallback_name = last(17).map(kotlin_as_string).unwrap_or_default();
    let name = if primary_name.is_empty() {
        fallback_name
    } else {
        primary_name
    };

    Ok((
        DescriptionKey { app_id, class_id, instance_id },
        TradeDescription {
            name,
            item_type: last(16).map(kotlin_as_string).unwrap_or_default(),
            icon_url: normalize_icon_url(&raw_icon),
            tradable: last(9).map(kotlin_as_bool).unwrap_or(false),
            marketable: last(25).map(kotlin_as_bool).unwrap_or(false),
        },
    ))
}

fn normalize_icon_url(raw: &str) -> String {
    if raw.is_empty() {
        String::new()
    } else if raw.starts_with("https://") {
        raw.to_owned()
    } else {
        let trimmed = raw.strip_prefix('/').unwrap_or(raw);
        format!("{ITEM_IMAGE_BASE}{trimmed}")
    }
}

fn max_timestamp(offer: &TradeOffer) -> i64 {
    offer.updated_at.max(offer.created_at)
}

fn kotlin_as_u64_varint(field: &ProtoFieldRef<'_>) -> u64 {
    match field.value {
        ProtoValueRef::Varint(value) => value,
        _ => 0,
    }
}

fn kotlin_as_long(field: &ProtoFieldRef<'_>) -> i64 {
    match field.value {
        ProtoValueRef::Varint(value) => value as i64,
        _ => 0,
    }
}

fn kotlin_as_int(field: &ProtoFieldRef<'_>) -> i32 {
    kotlin_as_long(field) as i32
}

fn kotlin_as_bool(field: &ProtoFieldRef<'_>) -> bool {
    kotlin_as_long(field) != 0
}

fn kotlin_as_string(field: &ProtoFieldRef<'_>) -> String {
    match field.value {
        ProtoValueRef::Bytes(bytes) => String::from_utf8_lossy(bytes).into_owned(),
        _ => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ProtoWriter;

    fn description(app_id: i64, class_id: u64, instance_id: u64, name: &str) -> ProtoWriter {
        let mut value = ProtoWriter::new();
        value.write_varint(1, app_id).unwrap();
        value.write_uint64(2, class_id).unwrap();
        value.write_uint64(3, instance_id).unwrap();
        value.write_string(6, "icon_a").unwrap();
        value.write_bool(9, true).unwrap();
        value.write_string(14, name).unwrap();
        value.write_string(16, "Rifle").unwrap();
        value.write_bool(25, true).unwrap();
        value
    }

    fn asset(app_id: i64, class_id: u64, instance_id: u64, amount: i64) -> ProtoWriter {
        let mut value = ProtoWriter::new();
        value.write_varint(1, app_id).unwrap();
        value.write_uint64(2, 2).unwrap();
        value.write_uint64(3, 8).unwrap();
        value.write_uint64(4, class_id).unwrap();
        value.write_uint64(5, instance_id).unwrap();
        value.write_varint(7, amount).unwrap();
        value
    }

    fn offer(id: u64, updated_at: i64, item: &ProtoWriter) -> ProtoWriter {
        let mut value = ProtoWriter::new();
        value.write_uint64(1, id).unwrap();
        value.write_varint(2, 12_345).unwrap();
        value.write_string(3, "Proto offer").unwrap();
        value.write_varint(5, 2).unwrap();
        value.write_message(7, item).unwrap();
        value.write_varint(9, 100).unwrap();
        value.write_varint(10, updated_at).unwrap();
        value
    }

    #[test]
    fn parses_descriptions_nested_assets_and_sorts_each_direction() {
        let desc = description(730, 10, 0, "Proto Item");
        let item = asset(730, 10, 0, 1);
        let older = offer(1001, 120, &item);
        let newer = offer(1002, 220, &item);
        let mut response = ProtoWriter::new();
        response.write_message(2, &older).unwrap();
        response.write_message(2, &newer).unwrap();
        response.write_message(3, &desc).unwrap();

        let parsed = parse_trade_offers(response.as_bytes()).unwrap();
        assert_eq!(parsed.received.len(), 2);
        assert_eq!(parsed.received[0].id, 1002);
        assert_eq!(parsed.received[1].id, 1001);
        let parsed_item = &parsed.received[0].items_to_receive[0];
        assert_eq!(parsed_item.name, "Proto Item");
        assert_eq!(parsed_item.item_type, "Rifle");
        assert_eq!(parsed_item.icon_url, format!("{ITEM_IMAGE_BASE}icon_a"));
        assert!(parsed_item.tradable);
        assert!(parsed_item.marketable);
    }

    #[test]
    fn asset_description_falls_back_to_instance_zero() {
        let desc = description(730, 10, 0, "Generic instance");
        let item = asset(730, 10, 99, 2);
        let received = offer(1, 1, &item);
        let mut response = ProtoWriter::new();
        response.write_message(2, &received).unwrap();
        response.write_message(3, &desc).unwrap();

        let parsed = parse_trade_offers(response.as_bytes()).unwrap();
        assert_eq!(parsed.received[0].items_to_receive[0].name, "Generic instance");
        assert_eq!(parsed.received[0].items_to_receive[0].amount, 2);
    }

    #[test]
    fn duplicate_offer_fields_keep_first_but_nested_maps_keep_last() {
        let mut item = asset(730, 10, 0, 1);
        item.write_varint(7, 3).unwrap();
        let mut value = offer(11, 120, &item);
        value.write_uint64(1, 22).unwrap();
        value.write_string(3, "later message").unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(2, &value).unwrap();

        let parsed = parse_trade_offers(response.as_bytes()).unwrap();
        let offer = &parsed.received[0];
        assert_eq!(offer.id, 11);
        assert_eq!(offer.message, "Proto offer");
        assert_eq!(offer.items_to_receive[0].amount, 3);
    }

    #[test]
    fn zero_or_missing_offer_ids_are_filtered_like_kotlin() {
        let item = asset(1, 1, 0, 1);
        let zero = offer(0, 1, &item);
        let mut missing = ProtoWriter::new();
        missing.write_varint(2, 1).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &zero).unwrap();
        response.write_message(2, &missing).unwrap();

        let parsed = parse_trade_offers(response.as_bytes()).unwrap();
        assert!(parsed.sent.is_empty());
        assert!(parsed.received.is_empty());
    }

    #[test]
    fn malformed_nested_payload_returns_error_for_kotlin_fallback() {
        let mut response = ProtoWriter::new();
        response.write_bytes(2, &[0x08, 0x80]).unwrap();
        assert!(matches!(
            parse_trade_offers(response.as_bytes()),
            Err(TradeOfferParseError::Proto(_))
        ));
    }

    #[test]
    fn partner_steam_id_offset_stays_within_kotlin_domain_model_range() {
        assert_eq!(STEAM_ID64_OFFSET + 12_345, 76_561_197_960_278_073);
    }
}
