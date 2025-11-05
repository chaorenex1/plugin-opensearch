import { axiosInstance } from '@halo-dev/api-client'
import { OpensearchConsoleV1alpha1Api } from './generated'

const OpensearchConsoleApiClient = {
  index: new OpensearchConsoleV1alpha1Api(undefined, '', axiosInstance),
}

export { OpensearchConsoleApiClient }
