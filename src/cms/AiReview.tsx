import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  FormControlLabel,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { usePermissions, useRecordContext, useRefresh } from 'react-admin';
import { api, json } from '../api/client';
import type { CmsNews } from './contracts';

export const aiGroups = [
  { id: 'suspicious', name: 'Высокий риск' },
  { id: 'doubtful', name: 'Нужна проверка' },
  { id: 'normal', name: 'Без явных рисков' },
  { id: 'waiting', name: 'Ещё не проверены' },
  { id: 'error', name: 'Ошибка проверки' },
];
export function AiBadge({ record }: { record: CmsNews }) {
  const group = record.aiState === 'done' ? record.aiGroup : record.aiState || 'waiting';
  return (
    <Chip
      size="small"
      label={aiGroups.find((g) => g.id === group)?.name || 'Ещё не проверена'}
      color={
        group === 'suspicious'
          ? 'error'
          : group === 'doubtful'
            ? 'warning'
            : group === 'normal'
              ? 'success'
              : 'default'
      }
    />
  );
}
const aiErrors: Record<string, string> = {
  authorization: 'MWAPI отклонил авторизацию. Проверьте ключ и доступ к модели.',
  rate_limit: 'MWAPI ограничил частоту запросов или доступную квоту.',
  configuration: 'MWAPI не принял параметры запроса. Проверьте название модели.',
  provider: 'Сервис нейросети временно не выполнил запрос.',
  timeout: 'Сервис нейросети не ответил за отведённое время.',
  network: 'Не удалось завершить соединение с сервисом нейросети.',
  invalid_json: 'Нейросеть вернула ответ в непонятном формате.',
  invalid_result: 'В ответе нейросети отсутствуют корректные оценки.',
  quote_mismatch: 'Нейросеть привела цитату, которая не совпала с текстом. Оценка не принята.',
  incomplete: 'Сервис вернул незавершённый ответ.',
};
export function AiDetails() {
  const record = useRecordContext<CmsNews>();
  if (!record) return null;
  const review = record.aiReview;
  return (
    <Box sx={{ my: 2, p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Typography sx={{ fontWeight: 700 }}>Предпроверка нейросетью</Typography>
        <AiBadge record={record} />
      </Stack>
      <Typography variant="body2" sx={{ mt: 1 }}>
        Оценка помогает выбрать порядок проверки. Решение принимает модератор.
      </Typography>
      {record.aiState === 'error' && (
        <Alert severity="warning" sx={{ mt: 1 }}>
          {aiErrors[record.aiErrorCode || ''] || 'Не удалось завершить проверку нейросетью.'}{' '}
          {(record.aiAttempts || 0) < 3
            ? 'Заявка будет проверена повторно автоматически.'
            : 'Автоматические попытки исчерпаны. Администратор может повторить проверку в параметрах предпроверки.'}{' '}
          Материал можно проверить вручную.
        </Alert>
      )}
      {record.aiState !== 'done' && record.aiState !== 'error' && (
        <Typography sx={{ mt: 1 }}>
          Оценки пока нет. При включённом подключении материал будет обработан в очереди.
        </Typography>
      )}
      {record.aiState === 'done' && review && (
        <>
          <Typography sx={{ mt: 1 }}>
            Новостной формат:{' '}
            {
              { fits: 'подходит', unclear: 'нужен контекст', off_topic: 'не соответствует' }[
                review.newsworthiness
              ]
            }
            .
          </Typography>
          <Typography>
            Признаки нарушений:{' '}
            {
              { low: 'явных признаков нет', uncertain: 'неоднозначные', high: 'высокий риск' }[
                review.violationRisk
              ]
            }
            .
          </Typography>
          <Box component="ul">
            {review.reasons.map((reason, i) => (
              <li key={i}>{reason}</li>
            ))}
          </Box>
          {review.evidence.map((quote, i) => (
            <Typography component="blockquote" key={i} sx={{ ml: 2, overflowWrap: 'anywhere' }}>
              «{quote}»
            </Typography>
          ))}
          {review.mediaUnchecked && (
            <Alert severity="warning">
              Фото и видео не проверены нейросетью. Их нужно посмотреть вручную; поэтому материал
              требует проверки.
            </Alert>
          )}
          <Typography variant="caption" color="text.secondary">
            Модель: {record.aiModel}.{' '}
            {record.aiCheckedAt && new Date(record.aiCheckedAt).toLocaleString('ru-RU')}
          </Typography>
        </>
      )}
    </Box>
  );
}
type Settings = { enabled: boolean; model: string; hasKey: boolean };
export function AiSettings() {
  const { permissions } = usePermissions();
  const [open, setOpen] = useState(false);
  if (permissions !== 'ADMIN') return null;
  return (
    <>
      <Button size="small" onClick={() => setOpen(true)}>
        Параметры предпроверки
      </Button>
      {open && <AiSettingsDialog onClose={() => setOpen(false)} />}
    </>
  );
}
function AiSettingsDialog({ onClose }: { onClose: () => void }) {
  const refresh = useRefresh();
  const [settings, setSettings] = useState<Settings>();
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api<Settings>('/ai-review/settings')
      .then(setSettings)
      .catch(() => setMessage('Не удалось загрузить настройки'));
  }, []);
  async function save() {
    if (!settings) return;
    setBusy(true);
    setMessage('');
    try {
      const result = await json<Settings>(
        '/ai-review/settings',
        { enabled: settings.enabled, model: settings.model },
        'PUT',
      );
      setSettings(result);
      setMessage('Настройки сохранены.');
      refresh();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Не удалось сохранить');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog open onClose={busy ? undefined : onClose} fullWidth maxWidth="sm">
      <DialogTitle>Параметры предпроверки</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {settings && (
            <>
              <Alert severity={settings.hasKey ? 'success' : 'info'}>
                {settings.hasKey
                  ? 'Ключ подключён на сервере. Просмотр и копирование через сайт недоступны.'
                  : 'Ключ ещё не подключён. Запустите «Настроить ключ Claude.cmd» на компьютере сервера, затем перезапустите NewsApp.'}
              </Alert>
              <TextField
                label="Название модели в MWAPI"
                value={settings.model}
                onChange={(e) => setSettings({ ...settings, model: e.target.value })}
                helperText="Точный ID модели из сервиса"
              />
              <FormControlLabel
                control={
                  <Switch
                    disabled={!settings.hasKey}
                    checked={settings.enabled}
                    onChange={(e) => setSettings({ ...settings, enabled: e.target.checked })}
                  />
                }
                label="Включить предпроверку"
              />
              <Typography variant="body2">
                При включении заголовок, текст и ссылка на источник ожидающих проверки заявок
                передаются MWAPI. Запросы расходуют баланс. Фото, видео и данные аккаунтов не
                отправляются.
              </Typography>
              <Button
                disabled={busy || !settings.enabled}
                onClick={async () => {
                  setBusy(true);
                  try {
                    await json('/ai-review/settings/retry', {});
                    setMessage('Ошибочные проверки возвращены в очередь');
                    refresh();
                  } catch {
                    setMessage('Не удалось повторить проверку');
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Повторить ошибочные проверки
              </Button>
            </>
          )}
          {message && <Alert severity="info">{message}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          Закрыть
        </Button>
        <Button variant="contained" disabled={busy || !settings} onClick={save}>
          Сохранить
        </Button>
      </DialogActions>
    </Dialog>
  );
}
