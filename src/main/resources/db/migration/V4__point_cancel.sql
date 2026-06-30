-- I1 fix: 적립 취소(보상) 거래 유형 추가.
-- 취소 시 redemption의 POINT_EARN을 역분개하고 PointAccount.balance를 차감하면서
-- 감사 추적용 CANCEL PointTransaction을 적재한다. enum에 CANCEL 값을 추가한다.
ALTER TABLE point_transactions
    MODIFY COLUMN type ENUM('EARN', 'CANCEL') NOT NULL;
