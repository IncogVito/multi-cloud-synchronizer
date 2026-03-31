export interface Photo {
  id: string;
  filename: string;
  size: number;
  createdDate: string;
  dimensions: {
    width: number;
    height: number;
  };
  assetToken: string;
}

export interface PhotoListResponse {
  photos: Photo[];
  total: number;
}
