use jni::{
    objects::{JByteArray, JClass},
    sys::jbyteArray,
    JNIEnv,
};
use monica_steam_core::trade_offer::{
    parse_trade_offers, TradeOffer, TradeOfferItem, TradeOffersSnapshot,
};
use std::ptr;

const TRADE_OFFERS_BRIDGE_MAGIC: &[u8; 4] = b"MTO1";

fn write_required_string(out: &mut Vec<u8>, value: &str) -> Option<()> {
    let bytes = value.as_bytes();
    let length = u32::try_from(bytes.len()).ok()?;
    out.extend_from_slice(&length.to_le_bytes());
    out.extend_from_slice(bytes);
    Some(())
}

fn serialize_trade_offers(snapshot: &TradeOffersSnapshot) -> Option<Vec<u8>> {
    let received_count = u32::try_from(snapshot.received.len()).ok()?;
    let sent_count = u32::try_from(snapshot.sent.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(TRADE_OFFERS_BRIDGE_MAGIC);
    out.extend_from_slice(&received_count.to_le_bytes());
    out.extend_from_slice(&sent_count.to_le_bytes());
    for offer in &snapshot.received {
        write_offer(&mut out, offer)?;
    }
    for offer in &snapshot.sent {
        write_offer(&mut out, offer)?;
    }
    Some(out)
}

fn write_offer(out: &mut Vec<u8>, offer: &TradeOffer) -> Option<()> {
    out.extend_from_slice(&offer.id.to_le_bytes());
    out.extend_from_slice(&offer.partner_account_id.to_le_bytes());
    out.extend_from_slice(&offer.state_code.to_le_bytes());
    out.extend_from_slice(&offer.created_at.to_le_bytes());
    out.extend_from_slice(&offer.updated_at.to_le_bytes());
    out.extend_from_slice(&offer.expiration_time.to_le_bytes());
    out.extend_from_slice(&offer.escrow_end_date.to_le_bytes());
    out.extend_from_slice(&offer.confirmation_method.to_le_bytes());
    write_required_string(out, &offer.message)?;
    write_items(out, &offer.items_to_give)?;
    write_items(out, &offer.items_to_receive)?;
    Some(())
}

fn write_items(out: &mut Vec<u8>, items: &[TradeOfferItem]) -> Option<()> {
    let count = u32::try_from(items.len()).ok()?;
    out.extend_from_slice(&count.to_le_bytes());
    for item in items {
        out.extend_from_slice(&item.app_id.to_le_bytes());
        out.extend_from_slice(&item.context_id.to_le_bytes());
        out.extend_from_slice(&item.asset_id.to_le_bytes());
        out.extend_from_slice(&item.class_id.to_le_bytes());
        out.extend_from_slice(&item.instance_id.to_le_bytes());
        out.extend_from_slice(&item.amount.to_le_bytes());
        let flags = (if item.tradable { 1u32 } else { 0 })
            | (if item.marketable { 1u32 << 1 } else { 0 })
            | (if item.missing { 1u32 << 2 } else { 0 });
        out.extend_from_slice(&flags.to_le_bytes());
        write_required_string(out, &item.name)?;
        write_required_string(out, &item.item_type)?;
        write_required_string(out, &item.icon_url)?;
    }
    Some(())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseTradeOffers(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(snapshot) = parse_trade_offers(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_trade_offers(&snapshot) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use monica_steam_core::trade_offer::{TradeOfferDirection, TradeOfferItem};

    #[test]
    fn trade_bridge_layout_is_stable() {
        let item = TradeOfferItem {
            app_id: 730,
            context_id: 2,
            asset_id: 8,
            class_id: 10,
            instance_id: 0,
            amount: 1,
            name: "Proto Item".to_string(),
            item_type: "Rifle".to_string(),
            icon_url: "https://cdn.example/icon".to_string(),
            tradable: true,
            marketable: true,
            missing: false,
        };
        let offer = TradeOffer {
            id: u64::MAX - 1,
            direction: TradeOfferDirection::Received,
            partner_account_id: 12_345,
            message: "Trade".to_string(),
            state_code: 2,
            items_to_give: Vec::new(),
            items_to_receive: vec![item],
            created_at: 100,
            updated_at: 120,
            expiration_time: 200,
            escrow_end_date: 0,
            confirmation_method: 1,
        };
        let encoded = serialize_trade_offers(&TradeOffersSnapshot {
            received: vec![offer],
            sent: Vec::new(),
        })
        .unwrap();

        assert_eq!(&encoded[0..4], b"MTO1");
        assert_eq!(u32::from_le_bytes(encoded[4..8].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(encoded[8..12].try_into().unwrap()), 0);
        assert_eq!(u64::from_le_bytes(encoded[12..20].try_into().unwrap()), u64::MAX - 1);
        assert_eq!(i64::from_le_bytes(encoded[20..28].try_into().unwrap()), 12_345);
        assert_eq!(i32::from_le_bytes(encoded[28..32].try_into().unwrap()), 2);
    }
}
