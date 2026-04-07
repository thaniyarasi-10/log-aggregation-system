declare const Chart: any;
import { LogEvent } from './types.js';

export class DashboardCharts {
    private errorRateChart: any;
    private responseTimeChart: any;
    private throughputChart: any;
    private errorDistributionChart: any;

    constructor() {
        this.initCharts();
    }

    public refreshTheme() {
        this.applyThemeDefaults();
        this.applyDatasetColors();
        [
            this.errorRateChart,
            this.responseTimeChart,
            this.throughputChart,
            this.errorDistributionChart
        ].forEach(chart => {
            if (chart) {
                chart.update('none');
            }
        });
    }

    private initCharts() {
        this.applyThemeDefaults();

        if (typeof Chart !== 'undefined') {
            Chart.defaults.color = this.getCssVar('--chart-text', '#94a3b8');
            Chart.defaults.borderColor = this.getCssVar('--chart-grid', 'rgba(148, 163, 184, 0.25)');
        }

        const createChart = (id: string, config: any) => {
            const canvas = document.getElementById(id) as HTMLCanvasElement;
            if (!canvas || typeof Chart === 'undefined') return null;
            return new Chart(canvas.getContext('2d'), config);
        };

        this.errorRateChart = createChart('errorRateChart', {
            type: 'line',
            data: { labels: [], datasets: [{ label: 'Error Rate (%)', data: [], borderColor: '#ef4444', tension: 0.4 }] },
            options: { responsive: true, maintainAspectRatio: false }
        });

        this.responseTimeChart = createChart('responseTimeChart', {
            type: 'bar',
            data: { labels: [], datasets: [{ label: 'Response Time (ms)', data: [], backgroundColor: '#3b82f6' }] },
            options: { responsive: true, maintainAspectRatio: false }
        });

        this.throughputChart = createChart('throughputChart', {
            type: 'line',
            data: { labels: [], datasets: [{ label: 'Logs/sec', data: [], borderColor: '#10b981', backgroundColor: 'rgba(16, 185, 129, 0.2)', fill: true, tension: 0.4 }] },
            options: { responsive: true, maintainAspectRatio: false }
        });

        this.errorDistributionChart = createChart('errorDistributionChart', {
            type: 'doughnut',
            data: { labels: [], datasets: [{ data: [], backgroundColor: ['#ef4444', '#eab308', '#10b981', '#3b82f6', '#8b5cf6'] }] },
            options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'right' } } }
        });

        this.applyDatasetColors();
    }

    public clear() {
        [this.errorRateChart, this.responseTimeChart, this.throughputChart].forEach(chart => {
            if (chart) {
                chart.data.labels = [];
                chart.data.datasets[0].data = [];
                chart.update('none');
            }
        });
        if (this.errorDistributionChart) {
            this.errorDistributionChart.data.labels = [];
            this.errorDistributionChart.data.datasets[0].data = [];
            this.errorDistributionChart.update('none');
        }
    }

    public updateBulk(logs: LogEvent[]) {
        this.clear();
        if (!logs.length) return;

        const sorted = [...logs].sort((a,b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
        const bucketSizeMs = 2000;
        const startTime = new Date(sorted[0].timestamp).getTime();
        const endTime = new Date(sorted[sorted.length-1].timestamp).getTime();
        const actualBucketSize = Math.max(bucketSizeMs, (endTime - startTime) / 30);
        
        if (actualBucketSize <= 0) {
           this.update(logs, logs);
           return;
        }

        const buckets: Record<number, LogEvent[]> = {};
        sorted.forEach(log => {
            const ts = new Date(log.timestamp).getTime();
            const bucketIndex = Math.floor((ts - startTime) / actualBucketSize);
            if (!buckets[bucketIndex]) buckets[bucketIndex] = [];
            buckets[bucketIndex].push(log);
        });

        for(let i=0; i<30; i++) {
           if (buckets[i]) {
               const bucketLogs = buckets[i];
               const d = new Date(startTime + i * actualBucketSize);
               const timeLabel = `${d.getHours()}:${d.getMinutes()}:${String(d.getSeconds()).padStart(2,'0')}`;
               
               const totalLogs = bucketLogs.length;
               const errors = bucketLogs.filter(l => l.level === 'ERROR').length;
               const errorRate = totalLogs > 0 ? (errors / totalLogs) * 100 : 0;
               
               const withResp = bucketLogs.filter(l => Number(l.responseTime) > 0);
               const avgResp = withResp.length ? withResp.reduce((acc, l) => acc + Number(l.responseTime), 0) / withResp.length : 0;
               const throughput = bucketLogs.length / (actualBucketSize / 1000);

               if (this.errorRateChart) this.addPoint(this.errorRateChart, timeLabel, errorRate);
               if (this.responseTimeChart) this.addPoint(this.responseTimeChart, timeLabel, avgResp);
               if (this.throughputChart) this.addPoint(this.throughputChart, timeLabel, throughput);
           }
        }
        
        if (this.errorRateChart) this.errorRateChart.update('none');
        if (this.responseTimeChart) this.responseTimeChart.update('none');
        if (this.throughputChart) this.throughputChart.update('none');

        this.updateDistribution(sorted);
    }

    public update(newLogs: LogEvent[], allLogs: LogEvent[]) {
        if (!newLogs.length) return;

        const now = new Date();
        const timeLabel = `${now.getHours()}:${now.getMinutes()}:${now.getSeconds()}`;

        const errors = newLogs.filter(l => l.level === 'ERROR').length;
        const errorRate = newLogs.length > 0 ? (errors / newLogs.length) * 100 : 0;
        if (this.errorRateChart) this.updateSeries(this.errorRateChart, timeLabel, errorRate);

        const withResp = newLogs.filter(l => Number(l.responseTime) > 0);
        const avgResp = withResp.length ? withResp.reduce((acc, l) => acc + Number(l.responseTime), 0) / withResp.length : 0;
        if (this.responseTimeChart) this.updateSeries(this.responseTimeChart, timeLabel, avgResp);

        let throughput = 0;
        if (newLogs.length > 1) {
             const first = new Date(newLogs[0].timestamp).getTime();
             const last = new Date(newLogs[newLogs.length - 1].timestamp).getTime();
             const diffS = Math.abs(last - first) / 1000;
             throughput = diffS > 0 ? (newLogs.length / diffS) : newLogs.length;
        } else {
             throughput = newLogs.length;
        }
        if (this.throughputChart) this.updateSeries(this.throughputChart, timeLabel, throughput);

        this.updateDistribution(allLogs);
    }

    private updateDistribution(allLogs: LogEvent[]) {
        const levelTotal: Record<string, number> = {};
        
        allLogs.forEach(l => {
            levelTotal[l.level] = (levelTotal[l.level] || 0) + 1;
        });
        
        const levelRates: Record<string, number> = {};
        const total = allLogs.length || 1;
        for (const lvl in levelTotal) {
             levelRates[lvl] = parseFloat(((levelTotal[lvl] / total) * 100).toFixed(2));
        }

        if (this.errorDistributionChart) {
            this.errorDistributionChart.data.labels = Object.keys(levelRates).map(lvl => `${lvl} (${levelRates[lvl]}%)`);
            this.errorDistributionChart.data.datasets[0].data = Object.values(levelRates);
            this.errorDistributionChart.update('none');
        }
    }

    private addPoint(chart: any, label: string, value: number) {
        chart.data.labels.push(label);
        chart.data.datasets[0].data.push(value);
        if (chart.data.labels.length > 30) {
            chart.data.labels.shift();
            chart.data.datasets[0].data.shift();
        }
    }

    private updateSeries(chart: any, label: string, value: number) {
        this.addPoint(chart, label, value);
        chart.update('none');
    }

    private applyThemeDefaults() {
        if (typeof Chart === 'undefined') return;
        Chart.defaults.color = this.getCssVar('--chart-text', '#94a3b8');
        Chart.defaults.borderColor = this.getCssVar('--chart-grid', 'rgba(148, 163, 184, 0.25)');
    }

    private applyDatasetColors() {
        if (this.errorRateChart) {
            this.errorRateChart.data.datasets[0].borderColor = this.getCssVar('--color-error', '#ef4444');
        }

        if (this.responseTimeChart) {
            this.responseTimeChart.data.datasets[0].backgroundColor = this.getCssVar('--accent-color', '#3b82f6');
        }

        if (this.throughputChart) {
            this.throughputChart.data.datasets[0].borderColor = this.getCssVar('--color-info', '#10b981');
            this.throughputChart.data.datasets[0].backgroundColor = this.getCssVar('--chart-throughput-fill', 'rgba(16, 185, 129, 0.2)');
        }

        if (this.errorDistributionChart) {
            this.errorDistributionChart.data.datasets[0].backgroundColor = [
                this.getCssVar('--color-error', '#ef4444'),
                this.getCssVar('--color-warn', '#eab308'),
                this.getCssVar('--color-info', '#10b981'),
                this.getCssVar('--accent-color', '#3b82f6'),
                this.getCssVar('--color-debug', '#8b5cf6')
            ];
        }
    }

    private getCssVar(name: string, fallback: string): string {
        const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
        return value || fallback;
    }
}
