import { MetricsResponse } from '../ts/types.js';

export class MetricsCards {
    public update(metrics: MetricsResponse) {
        const throughputAvgPerSec = metrics.throughputOverTime.length
            ? metrics.throughputOverTime.reduce((sum, point) => sum + (Number(point.throughputPerSecond) || 0), 0) /
                metrics.throughputOverTime.length
            : 0;

        this.animateValue('metric-error-rate', `${(Number(metrics.errorRate) || 0).toFixed(2)}%`);
        this.animateValue('metric-avg-resp', `${(Number(metrics.avgResponseTime) || 0).toFixed(0)}ms`);
        this.animateValue('metric-throughput', `${throughputAvgPerSec.toFixed(2)} req/s`);
        this.animateValue('metric-p95', `${(Number(metrics.p95Latency) || 0).toFixed(0)}ms`);
    }

    private animateValue(elementId: string, newValue: string) {
        const el = document.getElementById(elementId);
        if (el && el.innerText !== newValue) {
            el.innerText = newValue;
            el.classList.add('pulse');
            setTimeout(() => el.classList.remove('pulse'), 300);
        }
    }
}
