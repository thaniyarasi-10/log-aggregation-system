export class MetricsCards {
    update(logs) {
        if (!logs.length)
            return;
        let errorCount = 0;
        let totalTime = 0;
        const responseTimes = [];
        let timeWithResponse = 0;
        logs.forEach(log => {
            if (log.level === 'ERROR')
                errorCount++;
            // Handle possibility of undefined or string response times
            const rt = Number(log.responseTime);
            if (!isNaN(rt) && rt > 0) {
                totalTime += rt;
                responseTimes.push(rt);
                timeWithResponse++;
            }
        });
        const errorRate = logs.length > 0 ? (errorCount / logs.length) * 100 : 0;
        const avgResponseTime = timeWithResponse ? (totalTime / timeWithResponse) : 0;
        responseTimes.sort((a, b) => a - b);
        const p95Index = Math.floor(responseTimes.length * 0.95);
        const p95Latency = responseTimes.length ? responseTimes[p95Index] : 0;
        let throughput = 0;
        if (logs.length > 1) {
            const firstTime = new Date(logs[0].timestamp).getTime();
            const lastTime = new Date(logs[logs.length - 1].timestamp).getTime();
            const seconds = Math.abs(lastTime - firstTime) / 1000;
            if (seconds > 0) {
                throughput = logs.length / seconds;
            }
            else {
                throughput = logs.length;
            }
        }
        else {
            throughput = logs.length;
        }
        this.animateValue('metric-error-rate', errorRate.toFixed(2) + '%');
        this.animateValue('metric-avg-resp', avgResponseTime.toFixed(0) + 'ms');
        this.animateValue('metric-throughput', throughput.toFixed(0) + ' req/s');
        this.animateValue('metric-p95', p95Latency.toFixed(0) + 'ms');
    }
    animateValue(elementId, newValue) {
        const el = document.getElementById(elementId);
        if (el && el.innerText !== newValue) {
            el.innerText = newValue;
            el.classList.add('pulse');
            setTimeout(() => el.classList.remove('pulse'), 300);
        }
    }
}
