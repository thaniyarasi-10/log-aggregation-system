import type { MetricsResponse } from '../types';

type Props = {
  metrics: MetricsResponse;
};

export default function MetricsCards({ metrics }: Props) {
  const throughput = metrics.throughputOverTime.length
    ? metrics.throughputOverTime.reduce((sum, item) => sum + (Number(item.throughputPerSecond) || 0), 0)
      / metrics.throughputOverTime.length
    : 0;

  return (
    <section className="metrics-row">
      <article className="glass-panel metric-card">
        <span className="metric-title">Error Rate</span>
        <strong className="metric-value">{metrics.errorRate.toFixed(2)}%</strong>
      </article>

      <article className="glass-panel metric-card">
        <span className="metric-title">Avg Response Time</span>
        <strong className="metric-value">{Math.round(metrics.avgResponseTime)}ms</strong>
      </article>

      <article className="glass-panel metric-card">
        <span className="metric-title">Throughput</span>
        <strong className="metric-value">{throughput.toFixed(2)} req/s</strong>
      </article>

      <article className="glass-panel metric-card">
        <span className="metric-title">P95 Latency</span>
        <strong className="metric-value">{Math.round(metrics.p95Latency)}ms</strong>
      </article>
    </section>
  );
}
