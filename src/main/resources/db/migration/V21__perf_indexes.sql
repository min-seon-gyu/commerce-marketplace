-- 성능: 실측(EXPLAIN + IGNORE INDEX on/off)으로 효과를 검증한 인덱스 2종.

-- (1) 쿠폰 1인 사용한도 카운트: countByMemberIdAndPromotionIdAndStatus.
--     기존 idx_coupon_member(member_id, status)는 promotion_id를 커버하지 못해 회원의 (member,status) 전체를 훑고
--     promotion_id를 행에서 필터한다. 복합 인덱스로 (member_id, promotion_id, status)를 tight ref로 만든다.
create index idx_coupon_member_promo_status on coupons (member_id, promotion_id, status);

-- (2) 판매자 정산 매출 합산: sumSellerSalesInPeriod (order_lines를 seller_id로 훑어 orders와 조인).
--     seller_id·refunded 필터 + order_id 조인 + line_amount 합산을 인덱스만으로 처리(covering, Using index)해
--     판매자별 라인이 많을수록 힙 접근을 줄인다.
create index idx_orderline_seller_refund on order_lines (seller_id, refunded, order_id, line_amount);
