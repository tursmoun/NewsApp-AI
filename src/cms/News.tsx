import { useEffect } from 'react';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import { AiBadge, AiDetails, AiSettings, aiGroups } from './AiReview';
import { Check, Plus, X } from 'lucide-react';
import {
  Datagrid,
  DateField,
  Edit,
  EditButton,
  FunctionField,
  List,
  ListButton,
  ReferenceField,
  SaveButton,
  SearchInput,
  SelectInput,
  Show,
  ShowButton,
  SimpleForm,
  SimpleShowLayout,
  TextField,
  TextInput,
  Toolbar,
  TopToolbar,
  useRecordContext,
  useRefresh,
} from 'react-admin';
import { categories } from '../data/categories';
import { validateNewsDraft } from '../domain/news-validation';
import { json } from '../api/client';
import type { CmsNews } from './contracts';
import {
  ConfirmAction,
  dateOptions,
  EmptySection,
  NewsMedia,
  NewsStatus,
  RecordTitle,
  VisibilityAction,
} from './shared';

const statuses = [
  { id: 'pending', name: 'На проверке' },
  { id: 'main-pending', name: 'Проверка для общей ленты (администратор)' },
  { id: 'published', name: 'Опубликованы' },
  { id: 'rejected', name: 'Отклонены' },
  { id: 'hidden', name: 'Скрыты' },
];

export function NewsList() {
  const refresh = useRefresh();
  useEffect(() => {
    const timer = window.setInterval(refresh, 10000);
    return () => window.clearInterval(timer);
  }, [refresh]);
  return (
    <List
      title="Публикации"
      exporter={false}
      perPage={25}
      sort={{ field: 'aiPriority', order: 'DESC' }}
      filterDefaultValues={{ status: 'pending' }}
      filters={[
        <SelectInput
          source="aiGroup"
          label="Оценка нейросети"
          choices={aiGroups}
          alwaysOn
          key="aiGroup"
          emptyText="Все группы"
        />,
        <SearchInput source="q" placeholder="Заголовок или текст" alwaysOn key="q" />,
        <SelectInput
          source="status"
          label="Состояние"
          choices={statuses}
          alwaysOn
          key="status"
          emptyText="Все публикации"
        />,
      ]}
      actions={
        <TopToolbar>
          <AiSettings />
          <Button component="a" href="/create" startIcon={<Plus size={18} />}>
            Предложить новость
          </Button>
        </TopToolbar>
      }
      empty={
        <EmptySection
          title="Публикаций пока нет"
          text="Здесь появятся новости ваших пабликов и заявки на проверку."
        />
      }
    >
      <Datagrid
        rowClick="show"
        bulkActionButtons={false}
        sx={{ '& .column-title': { maxWidth: 380, overflowWrap: 'anywhere' } }}
      >
        <TextField source="title" label="Новость" />
        <FunctionField
          source="aiPriority"
          label="Приоритет проверки"
          render={(record: CmsNews) => <AiBadge record={record} />}
        />
        <FunctionField
          source="status"
          label="Состояние"
          render={(record: CmsNews) => <NewsStatus record={record} />}
        />
        <TextField source="author" label="Автор" />
        <ReferenceField
          source="communityId"
          reference="communities"
          label="Паблик"
          sortable={false}
          link={false}
          emptyText="Личная публикация"
        >
          <TextField source="name" />
        </ReferenceField>
        <DateField
          source="submittedAt"
          label="Отправлена"
          showTime
          locales="ru"
          options={dateOptions}
        />
        <ShowButton label="Открыть" />
      </Datagrid>
    </List>
  );
}

function ModerationActions() {
  const record = useRecordContext<CmsNews>();
  if (
    !record?.canModerate ||
    record.deleted ||
    (record.status !== 'pending' && !record.canReviewMain)
  )
    return null;
  const main = !!record.canReviewMain;
  const stage = main ? 'main/' : '';
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ my: 2 }}>
      <ConfirmAction
        label={
          main
            ? 'Одобрить для общей ленты'
            : record.ordinaryCommunity
              ? 'Одобрить в паблике'
              : 'Одобрить'
        }
        title="Опубликовать эту версию?"
        color="success"
        icon={<Check size={17} />}
        description={
          main
            ? 'Эта версия появится в общей ленте. Публикация в паблике уже одобрена.'
            : 'Версия будет опубликована после проверки. В обычном паблике общая лента требует отдельного одобрения администратора.'
        }
        request={() => json(`/admin/revisions/${record.revisionId}/${stage}approve`, {})}
        success="Новость одобрена"
      />
      <ConfirmAction
        label="Отклонить"
        title="Объясните причину отклонения"
        color="error"
        icon={<X size={17} />}
        description={
          main
            ? 'Отказ касается только общей ленты. Новость останется в паблике и подписках, прежняя одобренная версия общей ленты также сохранится.'
            : 'Автор увидит причину и сможет исправить материал. Ранее одобренная версия останется опубликованной.'
        }
        reason={{
          label: 'Причина',
          min: 5,
          max: 2000,
          helper: 'От 5 до 2 000 символов. Подскажите автору, что исправить.',
        }}
        request={(reason) =>
          json(`/admin/revisions/${record.revisionId}/${stage}reject`, { reason })
        }
        success="Заявка отклонена, автор уведомлён"
      />
    </Stack>
  );
}

function NewsToolbar({ editing = false }: { editing?: boolean }) {
  const record = useRecordContext<CmsNews>();
  return (
    <TopToolbar>
      <ListButton label="К публикациям" />
      {editing ? (
        <ShowButton label="Просмотр" />
      ) : (
        <EditButton label="Редактировать" disabled={record?.deleted || !record?.canEdit} />
      )}
      {record && <VisibilityAction newsId={record.id} hidden={record.deleted} />}
    </TopToolbar>
  );
}

function SourceLink() {
  const record = useRecordContext<CmsNews>();
  return record?.source ? (
    <Button component="a" href={record.source} target="_blank" rel="noreferrer">
      Открыть источник ↗
    </Button>
  ) : null;
}

function HiddenNotice() {
  const record = useRecordContext<CmsNews>();
  return record?.deleted ? (
    <Alert severity="info" sx={{ my: 2 }}>
      Новость скрыта. Чтобы редактировать её, сначала нажмите «Вернуть публикацию».
    </Alert>
  ) : null;
}

export function NewsShow() {
  return (
    <Show title={<RecordTitle fallback="Новость" />} actions={<NewsToolbar />}>
      <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1100 }}>
        <NewsStatus />
        <HiddenNotice />
        <AiDetails />
        <ModerationActions />
        <SimpleShowLayout sx={{ p: 0 }}>
          <TextField
            source="title"
            label="Заголовок"
            sx={{ fontSize: '1.6rem', fontWeight: 800, overflowWrap: 'anywhere' }}
          />
          <TextField source="author" label="Автор заявки" />
          <ReferenceField
            source="communityId"
            reference="communities"
            label="Паблик"
            emptyText="Личная публикация"
          >
            <TextField source="name" />
          </ReferenceField>
          <TextField source="category" label="Категория" />
          <TextField
            source="text"
            label="Текст новости"
            sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', lineHeight: 1.8 }}
          />
          <NewsMedia />
          <SourceLink />
          <TextField source="rejectionReason" label="Причина отклонения" emptyText="—" />
          <TextField
            source="mainRejectionReason"
            label="Причина отказа в общей ленте"
            emptyText="—"
          />
          <DateField
            source="submittedAt"
            label="Отправлена на проверку"
            showTime
            locales="ru"
            options={dateOptions}
          />
          <DateField
            source="publishedAt"
            label="Дата публикации"
            showTime
            locales="ru"
            options={dateOptions}
            emptyText="Ещё не опубликована"
          />
          <TextField source="views" label="Просмотры" />
        </SimpleShowLayout>
      </Box>
    </Show>
  );
}

function NewsEditForm() {
  const record = useRecordContext<CmsNews>();
  if (record?.deleted)
    return (
      <Box sx={{ p: 3 }}>
        <HiddenNotice />
      </Box>
    );
  return (
    <SimpleForm
      noValidate
      warnWhenUnsavedChanges
      validate={(values) =>
        validateNewsDraft({
          title: values.title || '',
          text: values.text || '',
          source: values.source || '',
          hasVideo: !!record?.media?.some((asset) => asset.kind === 'video'),
        })
      }
      toolbar={
        <Toolbar>
          <SaveButton label="Сохранить и отправить на проверку" />
        </Toolbar>
      }
    >
      <Alert severity="info" sx={{ mb: 3, width: '100%' }}>
        Изменения сохранятся как новая заявка. До одобрения читатели увидят прежнюю опубликованную
        версию.
      </Alert>
      <Typography variant="h6" sx={{ mb: 1 }}>
        Содержание новости
      </Typography>
      <TextInput
        source="title"
        label="Заголовок"
        fullWidth
        helperText="От 10 до 150 символов, включая пробелы и emoji."
        slotProps={{ htmlInput: { maxLength: 300 } }}
      />
      <SelectInput
        source="category"
        label="Категория"
        choices={categories
          .filter((category) => category !== 'Все')
          .map((category) => ({ id: category, name: category }))}
        fullWidth
      />
      <TextInput
        source="text"
        label="Текст новости"
        fullWidth
        multiline
        minRows={9}
        slotProps={{ htmlInput: { maxLength: 20000 } }}
        helperText="От 50 до 10 000 символов. С видео текст необязателен."
      />
      <TextInput
        source="source"
        label="Ссылка на источник"
        fullWidth
        helperText="HTTP или HTTPS. Для собственного материала можно оставить пустой."
      />
      <Typography variant="subtitle1" sx={{ mb: 2, fontWeight: 700 }}>
        Прикреплённые файлы сохранятся
      </Typography>
      <NewsMedia />
    </SimpleForm>
  );
}

export function NewsEdit() {
  return (
    <Edit
      title={<RecordTitle fallback="Редактирование новости" />}
      mutationMode="pessimistic"
      redirect="show"
      actions={<NewsToolbar editing />}
    >
      <NewsEditForm />
    </Edit>
  );
}
