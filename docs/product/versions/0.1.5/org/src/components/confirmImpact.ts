import { h } from 'vue';
import { Modal } from 'ant-design-vue';
import type { ImpactReport } from '../types';

export function confirmImpact(
  report: ImpactReport | null | undefined,
  onOk: () => void,
  opts?: { always?: boolean; okText?: string }
) {
  const show = opts?.always || report?.needConfirm;
  if (!show) {
    onOk();
    return;
  }
  const lines = report?.lines?.length ? report.lines : ['无下游表字段受影响。'];
  Modal.confirm({
    title: report?.title || '影响的下游字段',
    content: h(
      'ul',
      { style: 'margin: 0; padding-left: 18px; max-height: 240px; overflow: auto;' },
      lines.map((line) => h('li', { style: 'margin-bottom: 4px;' }, line))
    ),
    okText: opts?.okText || (report?.needConfirm ? '仍只写本表' : '确定'),
    cancelText: '取消',
    onOk,
  });
}
