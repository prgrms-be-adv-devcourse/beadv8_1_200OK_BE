-- 회원의 토큰 최소 버전을 지금 값보다 높을 때만 쓴다. 같거나 낮은 값은 버린다. (RedisTokenVersionStore, #561)
--
-- 그냥 덮어쓰면 늦게 도착한 쓰기(앞선 비밀번호 변경의 기록, 그 변경 전에 DB를 읽은 다시 채우기)가 값을 낮춰,
-- 두 변경 사이에 발급되어 폐기됐어야 할 토큰이 다시 통과한다. 읽고 비교하고 쓰는 일을 스크립트 하나로 해 다른 쓰기가 끼어들지 못하게 한다.
-- 지금 값이 숫자가 아니면 쓰는 쪽의 버그라 새 값으로 덮어쓴다(tonumber가 nil을 돌려준다).
-- 같은 버전은 TTL도 건드리지 않는다. 회원 쪽이 TTL을 변경 시각 기준 남은 시간으로 주므로 같은 버전의 만료 시각은 언제 써도 같다.
--
-- KEYS[1] member:token-version:v1:{memberId}
-- ARGV[1] 새 최소 버전 (숫자 문자열)
-- ARGV[2] TTL (밀리초, 1 이상)
-- 반환    1 = 썼다, 0 = 쓰지 않았다(같거나 낮은 값)
--
-- redis-cli로 직접 돌려 보기:
--   redis-cli --eval token-version-save-if-higher.lua member:token-version:v1:1 , 2 1860000

local current = tonumber(redis.call('GET', KEYS[1]))
if current == nil or tonumber(ARGV[1]) > current then
    redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
    return 1
end
return 0
