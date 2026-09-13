//! Read-only sorting of Workshop subscription metadata. Never receives credentials.

/// Descending signed score, lexicographic canonical decimal file ID, then input index.
pub fn subscription_order(scores: &[i64], ids: &[u64]) -> Option<Vec<i32>> {
    if scores.len() != ids.len() || scores.len() > 100_000 {
        return None;
    }
    let names: Vec<String> = ids.iter().map(u64::to_string).collect();
    let mut order: Vec<i32> = (0..scores.len() as i32).collect();
    order.sort_unstable_by(|a, b| {
        let a_index = *a as usize;
        let b_index = *b as usize;
        scores[b_index]
            .cmp(&scores[a_index])
            .then_with(|| names[a_index].cmp(&names[b_index]))
            .then_with(|| a.cmp(b))
    });
    Some(order)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn descending_scores_keep_lexicographic_id_ties() {
        assert_eq!(
            subscription_order(&[9, 9, 10, 9], &[2, 10, 8, 1]),
            Some(vec![2, 3, 1, 0])
        );
    }
    #[test]
    fn preserves_signed_scores_unsigned_ids_and_stability() {
        assert_eq!(
            subscription_order(
                &[i64::MIN, i64::MAX, 0, 0, 0],
                &[1, 2, u64::MAX, 9, u64::MAX]
            ),
            Some(vec![1, 2, 4, 3, 0])
        );
    }
    #[test]
    fn empty_and_invalid_lengths_are_bounded() {
        assert_eq!(subscription_order(&[], &[]), Some(vec![]));
        assert_eq!(subscription_order(&[1], &[]), None);
        assert_eq!(
            subscription_order(&vec![0; 100_001], &vec![0; 100_001]),
            None
        );
    }
}
