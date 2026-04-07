export function formatDate(isoString: string): string {
    const date = new Date(isoString);
    return date.toLocaleTimeString([], { hour12: false, hour: '2-digit', minute:'2-digit', second:'2-digit' }) + '.' + date.getMilliseconds().toString().padStart(3, '0');
}

export function escapeHtml(str: string): string {
    if (!str) return '';
    const div = document.createElement('div');
    div.innerText = str;
    return div.innerHTML;
}

export function getLevelClass(level: string): string {
    switch (level.toUpperCase()) {
        case 'ERROR': return 'tag-error';
        case 'WARN': return 'tag-warn';
        case 'INFO': return 'tag-info';
        case 'DEBUG': return 'tag-debug';
        default: return 'tag-default';
    }
}
