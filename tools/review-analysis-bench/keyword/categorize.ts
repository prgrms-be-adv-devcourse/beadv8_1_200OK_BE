// #387 키워드 목록을 하위 분류별로 만들기 위해, 생성 리뷰의 상품을 서비스 하위 분류(ProductCategory) 8개로 나눈다.
// 생성 데이터에는 신선·가공 구분만 있어서 상품 이름 규칙으로 나눈다. 결과는 사람이 검토하고, 애매한 상품은 따로 출력한다.
// 사용: node keyword/categorize.ts → data/keyword/product-categories.csv
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';

type Category =
  | 'GRAIN' | 'VEGETABLE' | 'FRUIT' | 'ROOT_CROP' | 'MUSHROOM' | 'NUT' | 'WILD_GREENS' | 'PROCESSED_AGRI'
  | 'EXCLUDED';

type Review = { id: string; set: string; product: string; productType: 'fresh' | 'processed' };

// 위에서부터 처음 맞는 규칙을 쓴다. 순서가 결과를 바꾼다: 고구마순은 고구마보다, 밤고구마는 밤보다 먼저 걸러야 한다
const FRESH_RULES: [RegExp, Category][] = [
  [/유정란/, 'EXCLUDED'], // 농산물이 아니다
  [/절임배추/, 'PROCESSED_AGRI'], // 생성 데이터에서 신선·가공 표시가 갈린다. 소금에 절인 가공품으로 맞춘다
  [/버섯/, 'MUSHROOM'],
  [/고구마순/, 'VEGETABLE'],
  [/감자|고구마/, 'ROOT_CROP'],
  [/잣|알밤|햇밤|깐밤|호두|땅콩|은행/, 'NUT'],
  [/고사리|곰취|냉이|취나물|참나물/, 'WILD_GREENS'],
  [/쌀|백미|현미|흑미|보리|귀리|잡곡|오곡|서리태|쥐눈이콩|옥수수/, 'GRAIN'],
  [/사과|나주배|신고배|원황배|단감|대봉감|반시|감귤|귤|한라봉|레드향|천혜향|카라향|하귤|오렌지|포도|거봉|샤인머스캣|딸기|토마토|수박|참외|블루베리|체리|키위|망고|아보카도|바나나|자두|복숭아|무화과|대추/, 'FRUIT'],
];

const PROCESSED_EXCLUDED = /요거트/; // 유제품은 농산 가공품이 아니다

// 규칙이 정하지만 사람이 한 번 더 봐야 하는 상품. 쇼핑몰마다 분류가 갈린다
const REVIEW_NEEDED: [RegExp, 'fresh' | 'processed', string][] = [
  [/옥수수/, 'fresh', '쌀·잡곡으로 둠. 채소로 보는 쇼핑몰도 있음'],
  [/토마토/, 'fresh', '과일로 둠. 채소로 보는 쇼핑몰도 있음'],
  [/수박|참외/, 'fresh', '과일로 둠 (과채류)'],
  [/고사리/, 'fresh', '데친 것도 나물로 둠. 건조·삶은 것은 가공품'],
  [/꿀(?!사과|고구마|참외|떡)/, 'processed', '농산 가공품으로 둠. 벌꿀은 농산물이 아니라고 볼 수 있음'],
  [/절임배추/, 'fresh', '신선으로 표시돼 있지만 가공품으로 둠'],
];

function categorize(review: Review): Category {
  if (review.productType === 'processed') {
    return PROCESSED_EXCLUDED.test(review.product) ? 'EXCLUDED' : 'PROCESSED_AGRI';
  }
  return FRESH_RULES.find(([pattern]) => pattern.test(review.product))?.[1] ?? 'VEGETABLE';
}

const reviews: Review[] = readFileSync('data/reviews.jsonl', 'utf8')
  .split('\n').filter(Boolean).map((line) => JSON.parse(line));

const products = new Map<string, { review: Review; count: number }>();
for (const review of reviews) {
  const entry = products.get(review.product) ?? { review, count: 0 };
  entry.count++;
  products.set(review.product, entry);
}

const rows = [...products.values()]
  .map(({ review, count }) => ({
    product: review.product,
    productType: review.productType,
    category: categorize(review),
    reviewCount: count,
    note: REVIEW_NEEDED.find(([pattern, type]) => type === review.productType && pattern.test(review.product))?.[2] ?? '',
  }))
  .sort((a, b) => a.category.localeCompare(b.category) || a.product.localeCompare(b.product, 'ko'));

const quote = (value: string | number) => `"${String(value).replaceAll('"', '""')}"`;
mkdirSync('data/keyword', { recursive: true });
writeFileSync(
  'data/keyword/product-categories.csv',
  ['product,productType,category,reviewCount,note', ...rows.map((r) =>
    [r.product, r.productType, r.category, r.reviewCount, r.note].map(quote).join(','))].join('\n') + '\n',
);

// 하위 분류별 상품·리뷰 수와 세트별 리뷰 수. 목록 만들기(추가 비교용 절반)에 리뷰가 너무 적은 분류를 찾는다
const byCategory = new Map<Category, { products: number; reviews: Record<string, number> }>();
const categoryOf = new Map(rows.map((r) => [r.product, r.category]));
for (const row of rows) {
  const entry = byCategory.get(row.category) ?? { products: 0, reviews: {} };
  entry.products++;
  byCategory.set(row.category, entry);
}
for (const review of reviews) {
  const entry = byCategory.get(categoryOf.get(review.product)!)!;
  entry.reviews[review.set] = (entry.reviews[review.set] ?? 0) + 1;
}
console.log('category\tproducts\ttuning\teval\textra');
for (const [category, { products: count, reviews: sets }] of [...byCategory].sort()) {
  console.log([category, count, sets.tuning ?? 0, sets.eval ?? 0, sets.extra ?? 0].join('\t'));
}
console.log('\n검토 필요:');
for (const row of rows.filter((r) => r.note)) console.log(`${row.category}\t${row.product}\t${row.note}`);
