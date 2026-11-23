SET NAMES utf8mb4;
-- Demo grooming bookings. Contact and pet details are placeholders because they were not provided.
INSERT IGNORE INTO grooming_bookings
  (id, guardian_name, phone, pet_name, pet_type, pet_weight, service, preferred_at, memo, status)
VALUES
  (1, '정길동', '미입력', '미정', '미정', NULL, '목욕', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 1 DAY), '10:00:00'), '샘플 예약', 'PENDING'),
  (2, '한남솔', '미입력', '미정', '미정', NULL, '부분 미용', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 2 DAY), '11:00:00'), '샘플 예약', 'PENDING'),
  (3, '이ㅈ무', '미입력', '미정', '미정', NULL, '전체 미용', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 3 DAY), '13:00:00'), '샘플 예약', 'PENDING'),
  (4, '신정', '미입력', '미정', '미정', NULL, '고양이 케어', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 4 DAY), '14:00:00'), '샘플 예약', 'PENDING'),
  (5, '정인정', '미입력', '미정', '미정', NULL, '목욕', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 5 DAY), '15:00:00'), '샘플 예약', 'PENDING'),
  (6, 'ㅈ현ㅈ', '미입력', '미정', '미정', NULL, '상담 후 결정', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 6 DAY), '16:00:00'), '샘플 예약', 'PENDING'),
  (7, '조현준', '조현준', '강아지', '10kg', NULL, '상담 후 결정', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 6 DAY), '16:00:00'), '샘플 예약', 'PENDING'),
  (8, 'ㅈ인ㅈ', '조현준', '개냥이', '5kg', NULL, '상담 후 결정', TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 6 DAY), '16:00:00'), '샘플 예약', 'PENDING');