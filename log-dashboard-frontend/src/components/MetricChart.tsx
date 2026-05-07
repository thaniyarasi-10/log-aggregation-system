import {
  CategoryScale,
  Chart as ChartJS,
  Filler,
  Legend,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip
} from 'chart.js';
import { Line } from 'react-chartjs-2';
import type { LogEvent } from '../types';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler);

type Props = {
  title: string;
  logs: LogEvent[];
};

export default function MetricChart({ title, logs }: Props) {
  const ordered = [...logs].sort(
    (a, b) =>
      new Date(a["@timestamp"] ?? 0).getTime() - new Date(b["@timestamp"] ?? 0).getTime()
  );
  const labels = ordered.map((item) =>
    item["@timestamp"]
      ? new Date(item["@timestamp"]).toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata" })
      : "N/A"
  );
  const values = ordered.map((item) => Number(item.responseTime || 0));

  return (
    <section className="panel chart-panel">
      <h3>{title}</h3>
      <Line
        data={{
          labels,
          datasets: [
            {
              label: 'Latency (ms)',
              data: values,
              borderColor: '#0f766e',
              backgroundColor: 'rgba(15, 118, 110, 0.18)',
              tension: 0.25,
              fill: true
            }
          ]
        }}
        options={{
          responsive: true,
          plugins: {
            legend: { display: true }
          }
        }}
      />
    </section>
  );
}
