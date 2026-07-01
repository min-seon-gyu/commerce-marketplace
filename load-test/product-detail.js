// 상품 상세 API 읽기 부하테스트 (k6)
//
// 앱을 로컬 8080에 띄운 뒤 실행한다. (docs/05-load-test.md 참고)
//   docker:  docker run --rm -i --add-host=host.docker.internal:host-gateway grafana/k6 run - < load-test/product-detail.js
//   native:  BASE_URL=http://localhost:8080 k6 run load-test/product-detail.js
//
// 환경변수: BASE_URL(기본 host.docker.internal:8080), PRODUCTS(시드 상품 수, 기본 2000), VUS(목표 동시 사용자, 기본 100)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PRODUCTS = Number(__ENV.PRODUCTS || 2000);
const TARGET = Number(__ENV.VUS || 100);

export const options = {
  scenarios: {
    detail: {
      executor: 'ramping-vus',
      exec: 'detail',
      startVUs: 0,
      stages: [
        { duration: '20s', target: TARGET },
        { duration: '40s', target: TARGET },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 상품 상세 = 상품 1건 + SKU 목록 + 재고(배치 조회). 캐시 없이 매 요청 DB 왕복.
export function detail() {
  const id = Math.floor(Math.random() * PRODUCTS) + 1;
  const r = http.get(`${BASE}/api/v1/products/${id}`, { tags: { name: 'detail' } });
  check(r, { '200': (res) => res.status === 200 });
}
