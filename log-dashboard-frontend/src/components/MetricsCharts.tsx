import {
  ArcElement,
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Filler,
  Legend,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip
} from 'chart.js';
import { Bar, Doughnut, Line } from 'react-chartjs-2';
import type { MetricsResponse } from '../types';

ChartJS.register(
  ArcElement,
  BarElement,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Legend,
  Filler
);

type Props = {
  metrics: MetricsResponse;
};

function formatLabel(value: string): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) {
    return value;
  }
  return date.toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });
}

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      labels: {
        color: 'rgb(148, 163, 184)'
      }
    }
  },
  scales: {
    x: {
      ticks: {
        color: 'rgb(148, 163, 184)',
        maxRotation: 45,
        minRotation: 45
      },
      grid: {
        color: 'rgba(148, 163, 184, 0.15)'
      }
    },
    y: {
      ticks: {
        color: 'rgb(148, 163, 184)'
      },
      grid: {
        color: 'rgba(148, 163, 184, 0.15)'
      }
    }
  }
} as const;

export default function MetricsCharts({ metrics }: Props) {
  const timeline = [...metrics.throughputOverTime].sort(
    (a, b) => new Date(a.time).getTime() - new Date(b.time).getTime()
  );

  const labels = timeline.map((point) => formatLabel(point.time));

  const errorRateData = {
    labels,
    datasets: [
      {
        label: 'Error Rate (%)',
        data: timeline.map((point) => Number(point.errorRate) || 0),
        borderColor: '#ef4444',
        backgroundColor: 'rgba(239, 68, 68, 0.2)',
        fill: false,
        tension: 0.35
      }
    ]
  };

  const responseData = {
    labels,
    datasets: [
      {
        label: 'Response Time (ms)',
        data: timeline.map((point) => Number(point.avgResponseTime) || 0),
        backgroundColor: '#3b82f6'
      }
    ]
  };

  const throughputData = {
    labels,
    datasets: [
      {
        label: 'Logs/sec',
        data: timeline.map((point) => Number(point.throughputPerSecond) || 0),
        borderColor: '#10b981',
        backgroundColor: 'rgba(16, 185, 129, 0.2)',
        fill: true,
        tension: 0.35
      }
    ]
  };

  const levels = [...metrics.levelDistribution].sort((a, b) => b.count - a.count);
  const levelData = {
    labels: levels.map((entry) => `${entry.level} (${entry.count})`),
    datasets: [
      {
        data: levels.map((entry) => entry.count),
        backgroundColor: ['#ef4444', '#eab308', '#10b981', '#3b82f6', '#8b5cf6']
      }
    ]
  };

  return (
    <>
      <section className="charts-row">
        <article className="glass-panel chart-container">
          <h3>Error Rate Over Time</h3>
          <div className="chart-wrapper">
            <Line data={errorRateData} options={chartOptions} />
          </div>
        </article>

        <article className="glass-panel chart-container">
          <h3>Response Time Distribution</h3>
          <div className="chart-wrapper">
            <Bar data={responseData} options={chartOptions} />
          </div>
        </article>
      </section>

      <section className="charts-row">
        <article className="glass-panel chart-container">
          <h3>Throughput Over Time</h3>
          <div className="chart-wrapper">
            <Line data={throughputData} options={chartOptions} />
          </div>
        </article>

        <article className="glass-panel chart-container">
          <h3>Level Distribution</h3>
          <div className="chart-wrapper">
            <Doughnut data={levelData} options={{ responsive: true, maintainAspectRatio: false }} />
          </div>
        </article>
      </section>
    </>
  );
}
