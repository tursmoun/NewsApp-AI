import type {
  DataProvider,
  GetListParams,
  GetManyParams,
  GetOneParams,
  RaRecord,
  UpdateParams,
} from 'react-admin';
import { api } from '../api/client.ts';

export type CmsRequest = <T>(path: string, options?: RequestInit) => Promise<T>;
const resources = new Set(['news', 'communities', 'reports', 'audit', 'users']);

function resourcePath(resource: string) {
  if (!resources.has(resource)) throw new Error('Этот раздел управления недоступен.');
  return `/cms/${resource}`;
}

export function cmsListQuery(params: Pick<GetListParams, 'pagination' | 'sort' | 'filter'>) {
  const query = new URLSearchParams({
    page: String(params.pagination?.page || 1),
    perPage: String(params.pagination?.perPage || 25),
    sort: params.sort?.field || 'createdAt',
    order: params.sort?.order || 'DESC',
  });
  for (const key of ['q', 'status', 'blocked', 'role', 'aiGroup']) {
    const value: unknown = params.filter?.[key];
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
  }
  return query.toString();
}

export function cmsUpdatePayload(
  resource: string,
  data: Record<string, unknown>,
  previous: Record<string, unknown> = {},
) {
  if (resource === 'news') {
    const record = { ...previous, ...data };
    const media = record.media as { id: string }[] | undefined;
    return {
      title: record.title,
      text: record.text,
      category: record.category,
      source: typeof record.source === 'string' ? record.source.trim() : '',
      communityId: Object.hasOwn(previous, 'communityId')
        ? (previous.communityId ?? null)
        : (record.communityId ?? null),
      mediaIds: record.mediaIds ?? media?.map((asset) => asset.id) ?? [],
    };
  }
  const fields: Record<string, string[]> = {
    communities: ['name', 'description', 'dailyLimit', 'official'],
    reports: ['resolution'],
    users: ['name', 'bio'],
  };
  if (!fields[resource]) throw new Error('Этот раздел доступен только для чтения.');
  return Object.fromEntries(
    fields[resource]
      .filter((key) => data[key] !== undefined && data[key] !== previous[key])
      .map((key) => [key, data[key]]),
  );
}

export function createCmsDataProvider(request: CmsRequest = api): DataProvider {
  const getOne = async <RecordType extends RaRecord = RaRecord>(
    resource: string,
    params: GetOneParams,
  ) => ({
    data: await request<RecordType>(`${resourcePath(resource)}/${encodeURIComponent(params.id)}`),
  });
  const unavailable = async () => {
    throw new Error('Для этого действия используйте отдельную кнопку в карточке.');
  };
  return {
    getList: async <RecordType extends RaRecord = RaRecord>(
      resource: string,
      params: GetListParams,
    ) => {
      const result = await request<{ items: RecordType[]; total: number }>(
        `${resourcePath(resource)}?${cmsListQuery(params)}`,
      );
      return { data: result.items, total: result.total };
    },
    getOne,
    getMany: async <RecordType extends RaRecord = RaRecord>(
      resource: string,
      params: GetManyParams,
    ) => ({
      data: await Promise.all(
        params.ids.map(async (id) => (await getOne<RecordType>(resource, { id })).data),
      ),
    }),
    getManyReference: unavailable,
    update: async <RecordType extends RaRecord = RaRecord>(
      resource: string,
      params: UpdateParams<RecordType>,
    ) => {
      await request(`${resourcePath(resource)}/${encodeURIComponent(params.id)}`, {
        method: 'PATCH',
        body: JSON.stringify(cmsUpdatePayload(resource, params.data, params.previousData)),
      });
      return getOne<RecordType>(resource, { id: params.id });
    },
    create: unavailable,
    updateMany: unavailable,
    delete: unavailable,
    deleteMany: unavailable,
  };
}

export const cmsDataProvider = createCmsDataProvider();
