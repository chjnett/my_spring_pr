import { randomUUID } from 'node:crypto';

const baseUrl = process.env.BASE_URL ?? 'http://localhost:8081';
const serviceKey = process.env.SECURITY_EVENT_INGEST_API_KEY;
const count = Number(process.env.SEED_COUNT ?? '1000000');
const batchSize = Number(process.env.SEED_BATCH_SIZE ?? '500');

if (!serviceKey) throw new Error('SECURITY_EVENT_INGEST_API_KEY 환경 변수가 필요합니다.');
if (!Number.isInteger(count) || count < 1) throw new Error('SEED_COUNT는 양의 정수여야 합니다.');
if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 500) {
  throw new Error('SEED_BATCH_SIZE는 1~500이어야 합니다.');
}

const startedAt = Date.now();
for (let offset = 0; offset < count; offset += batchSize) {
  const events = Array.from({ length: Math.min(batchSize, count - offset) }, (_, index) => {
    const sequence = offset + index;
    const actorId = (sequence % 10000) + 1;
    const type = sequence % 17 === 0 ? 'ACCESS_DENIED' : 'LOGIN_FAILED';
    return {
      eventId: randomUUID(),
      eventType: type,
      actorId,
      sourceIp: `198.51.${Math.floor(actorId / 250)}.${(actorId % 250) + 1}`,
      outcome: type === 'ACCESS_DENIED' ? 'DENIED' : 'FAILURE',
      occurredAt: new Date(Date.now() - ((sequence % 86400) * 1000)).toISOString(),
      attributes: { reasonCode: 'BENCHMARK_SEED' },
    };
  });
  const response = await fetch(`${baseUrl}/api/internal/security-events:batch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-secureflow-ingest-key': serviceKey },
    body: JSON.stringify({ events }),
  });
  if (!response.ok) throw new Error(`seed failed at ${offset}: ${response.status} ${await response.text()}`);
  if ((offset + events.length) % 10000 === 0 || offset + events.length === count) {
    console.log(`seeded ${offset + events.length}/${count}`);
  }
}
console.log(`complete: ${count} events in ${((Date.now() - startedAt) / 1000).toFixed(1)}s`);
